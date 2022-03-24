(ns hipflask-test
  (:require [cljs.test :refer (deftest is async use-fixtures)]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.hipflask :refer [put pouchdb pouch-atom done? update-keys watch-changes]]))

(use-fixtures :once
  {;:before (fn [] (async done (.destroy (pouchdb "test") #(println "before" (done)))))
   :after  (fn [] (async done (.destroy (pouchdb "testdb") done)))})

(deftest test-put
  (async done
         (go
           (let [db (pouchdb "testdb")
                 res (<p! (put db {:_id "test" :number 1}))]
             (is (.-ok res))
             (done)))))

(deftest atom-init
  (let [db (pouchdb "testdb")
        pa1 (pouch-atom db "group")] ; make the first atom
    (watch-changes db {"group" pa1})
    (async done
           (go
             ; put a few docs in the db (test changes handler)
             (doseq [i (range 3)]
               (<p! (put db {:_id (str "group:doc" i) :number 0})))
             (let [pa2 (pouch-atom db "group")] ; make another atom
               (watch-changes db {"group" pa2})
               ; assoc a few docs into the atom
               (doseq [i (range 3 6)
                       :let [id (str "group:doc" i)]]
                 (<! (swap! pa2 assoc id {:number 0})))
               (<! (swap! pa2 into
                          {"group:doc6" {:number 0}
                           "group:doc7" {:number 0}
                           "group:doc8" {:number 0}}))
               ; wait for changes to propagate, and check equality
               ; also verify "test" didn't get in
               (js/setTimeout #(do (is (=  @pa1 @pa2))
                                   (is (not (contains? @pa1 "test")))
                                   (done))
                              100))))))

(deftest atom-update
  (let [n 100
        db (pouchdb "testdb")
        pa (pouch-atom db "group")] ; make an atom
    (async done
           (go
             (let [chs1 (doall (for [_ (range n)]
                                 (swap! pa update-in ["group:doc0" :number] inc)))
                   chs2 (doall (for [_ (range n)]
                                 (swap! pa update-keys #{"group:doc1" "group:doc2"} update :number inc)))]
               (doseq [ch chs1] (<! ch))
               (doseq [ch chs2] (<! ch))
               (is (= n (get-in @pa ["group:doc0" :number])))
               (is (= n (get-in @pa ["group:doc1" :number])))
               (is (= n (get-in @pa ["group:doc2" :number])))
               (done))))))

(deftest atom-delete
  (let [db (pouchdb "testdb")
        pa1 (pouch-atom db "group")
        pa2 (pouch-atom db "group")]
    (watch-changes db {"group" pa1})
    (watch-changes db {"group" pa2})
    (async done
           (go
             (<! (swap! pa1 dissoc "group:doc1"))
             (js/setTimeout #(do (is (=  @pa1 @pa2))
                                 (is (not (contains? @pa1 "group:doc1")))
                                 (is (not (contains? @pa2 "group:doc1")))
                                 (done))
                            100)))))

(deftest atom-watch
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "group")]
    (add-watch pa :foo (fn [_ ref _ _] (is (identical? ref pa))))
    (async done
           (go
             (<! (swap! pa update-in ["group:doc0" :number] inc))
             (done)))))

(deftest atom-identity
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "group")]
    (async done
           (go
             (<! (done? pa)) ; wait for it to initialise before getting dereference
             (let [de @pa
                   rev (get-in de ["group:doc0" :_rev])]
               (<! (swap! pa update "group:doc0" identity))
               (is (= rev (get-in @pa ["group:doc0" :_rev])))
               (is (identical? de  @pa)))
             (done)))))