(ns hipflask-test
  (:require [cljs.test :refer (deftest is async use-fixtures)]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.hipflask :refer [put pouchdb pouch-atom init? update-keys]]))

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
    (async done
           (go
             (<! (init? pa1)) ; wait for it to initialise
             ; put a few docs in the db (test changes handler)
             (doseq [i (range 3)]
               (<p! (put db {:_id (str "group/doc" i) :number 0})))
             (let [pa2 (pouch-atom db "group")] ; make another atom
               (<! (init? pa2)); wait for it to initialize
               ; assoc a few docs into the atom
               (doseq [i (range 3 6)
                       :let [id (str "group/doc" i)]]
                 (<! (swap! pa2 assoc id {"number" 0})))
               ; wait for changes to propagate, and check equality
               ; also verify "test" didn't get in
               (js/setTimeout #(do (is (=  @pa1 @pa2))
                                   (is (not (contains? @pa1 "test")))
                                   (done))
                              100))))))

(deftest atom-update
  (let [n 10
        db (pouchdb "testdb")
        pa (pouch-atom db "group")] ; make an atom
    (async done
           (go
             (<! (init? pa)) ; wait for it to initialise
             (let [chs1 (doall (for [_ (range n)]
                                 (swap! pa update-in ["group/doc0" "number"] inc)))
                   chs2 (doall (for [_ (range n)]
                                 (swap! pa update-keys #{"group/doc1" "group/doc2"} update "number" inc)))]
               (doseq [ch chs1] (<! ch))
               (doseq [ch chs2] (<! ch))
               (is (= n (get-in @pa ["group/doc0" "number"])))
               (is (= n (get-in @pa ["group/doc1" "number"])))
               (is (= n (get-in @pa ["group/doc2" "number"])))
               (done))))))

(deftest atom-delete
  (let [db (pouchdb "testdb")
        pa1 (pouch-atom db "group")
        pa2 (pouch-atom db "group")]
    (async done
           (go
             (<! (init? pa1))
             (<! (init? pa2))
             (<! (swap! pa1 dissoc "group/doc1"))
             (js/setTimeout #(do (is (=  @pa1 @pa2))
                                 (is (not (contains? @pa1 "group/doc1")))
                                 (is (not (contains? @pa2 "group/doc1")))
                                 (done))
                            100)))))

(deftest atom-watch
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "group")]
    (add-watch pa :foo (fn [_ ref _ _] (is (identical? ref pa))))
    (async done
           (go
             (<! (init? pa))
             (<! (swap! pa update-in ["group/doc0" "number"] inc))
             (done)))))