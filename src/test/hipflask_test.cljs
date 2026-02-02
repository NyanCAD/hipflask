; SPDX-FileCopyrightText: 2022 Pepijn de Vos
;
; SPDX-License-Identifier: MPL-2.0

(ns hipflask-test
  (:require [cljs.test :refer (deftest is async use-fixtures)]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [nyancad.hipflask :refer [put pouchdb pouch-atom done? update-keys
                                      watch-changes json->clj get-group
                                      get-mango-group]]))

(use-fixtures :once
  {;:before (fn [] (async done (.destroy (pouchdb "test") #(println "before" (done)))))
   :after  (fn [] (async done (.destroy (pouchdb "testdb") done)))})

;; Tests for json->clj function
(deftest test-json->clj-nil
  (is (nil? (json->clj nil))))

(deftest test-json->clj-primitive
  (is (= 42 (json->clj 42)))
  (is (= "hello" (json->clj "hello")))
  (is (= true (json->clj true))))

(deftest test-json->clj-array
  (let [js-arr #js [1 2 3]
        result (json->clj js-arr)]
    (is (vector? result))
    (is (= [1 2 3] result))))

(deftest test-json->clj-object
  (let [js-obj #js {:a 1 :b 2}
        result (json->clj js-obj)]
    (is (map? result))
    (is (= {:a 1 :b 2} result))))

(deftest test-json->clj-nested
  (let [js-obj #js {:a #js {:b #js [1 2 #js {:c 3}]}}
        result (json->clj js-obj)]
    (is (= {:a {:b [1 2 {:c 3}]}} result))))

(deftest test-json->clj-single-char-keys
  ;; This is the main reason for json->clj - single char keys are safe
  (let [js-obj #js {"a" 1 "b" 2 "x" 3}
        result (json->clj js-obj)]
    (is (= {:a 1 :b 2 :x 3} result))))

;; Tests for PouchDB operations
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
    (watch-changes db pa1)
    (async done
           (go
             ; put a few docs in the db (test changes handler)
             (doseq [i (range 3)]
               (<p! (put db {:_id (str "group:doc" i) :number 0})))
             (let [pa2 (pouch-atom db "group")] ; make another atom
               (watch-changes db pa2)
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
    (watch-changes db pa1)
    (watch-changes db pa2)
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

(deftest atom-type
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "group" (atom (sorted-map)))]
    (async done
           (go
             (<! (done? pa)) ; wait for it to initialise before getting dereference
             (<! (swap! pa update-in ["group:doc0" :number] inc))
             (is (= (type @pa) cljs.core/PersistentTreeMap)) ; cache type is maintained
             (done)))))

;; Test for get-group with limit
(deftest test-get-group-with-limit
  (let [db (pouchdb "testdb")]
    (async done
           (go
             ; First ensure we have some documents
             (doseq [i (range 5)]
               (<p! (put db {:_id (str "limitgroup:doc" i) :number i})))
             ; Get with limit
             (let [result (<! (get-group db "limitgroup" 2))]
               (is (= 2 (count result))))
             ; Get all
             (let [result (<! (get-group db "limitgroup"))]
               (is (>= (count result) 5)))
             (done)))))

;; Test for get-group maintaining target type
(deftest test-get-group-target-type
  (let [db (pouchdb "testdb")]
    (async done
           (go
             ; Get into sorted-map
             (let [result (<! (get-group db "limitgroup" nil (sorted-map)))]
               (is (= (type result) cljs.core/PersistentTreeMap)))
             (done)))))

;; Test for validator - valid data passes
(deftest test-validator-pass
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "valgroup")]
    ;; Set a validator that requires :number to be positive
    (set! (.-validator pa) (fn [docs]
                             (every? (fn [[_ doc]]
                                       (or (nil? doc)
                                           (nil? (:number doc))
                                           (pos? (:number doc))))
                                     docs)))
    (async done
           (go
             (<! (done? pa))
             ;; This should pass - positive number
             (<! (swap! pa assoc "valgroup:test1" {:number 5}))
             (is (= 5 (get-in @pa ["valgroup:test1" :number])))
             (done)))))

;; Test for validator - invalid data throws
(deftest test-validator-reject
  (let [db (pouchdb "testdb")
        pa (pouch-atom db "valgroup2")
        error-thrown (atom false)]
    ;; Set a validator that requires :number to be positive
    (set! (.-validator pa) (fn [docs]
                             (every? (fn [[_ doc]]
                                       (or (nil? doc)
                                           (nil? (:number doc))
                                           (pos? (:number doc))))
                                     docs)))
    (async done
           (go
             (<! (done? pa))
             ;; This should throw - negative number
             (try
               (<! (swap! pa assoc "valgroup2:test1" {:number -5}))
               (catch :default e
                 (reset! error-thrown true)))
             ;; Give async ops time to complete and error to propagate
             (js/setTimeout #(do
                               ;; The document should not be in the cache (validator rejected it)
                               (is (or @error-thrown
                                       (nil? (get @pa "valgroup2:test1"))))
                               (done))
                            100)))))

;; Test for get-mango-group
(deftest test-get-mango-group
  (let [db (pouchdb "testdb")]
    (async done
           (go
             ;; Create an index first
             (<p! (.createIndex db (clj->js {:index {:fields ["type"]}})))
             ;; Insert some documents with a type field
             (<p! (put db {:_id "mango:doc1" :type "test" :value 1}))
             (<p! (put db {:_id "mango:doc2" :type "test" :value 2}))
             (<p! (put db {:_id "mango:doc3" :type "other" :value 3}))
             ;; Query for type = "test"
             (let [result (<! (get-mango-group db {:type "test"}))]
               (is (= 2 (count result)))
               (is (contains? result "mango:doc1"))
               (is (contains? result "mango:doc2"))
               (is (not (contains? result "mango:doc3"))))
             (done)))))

;; Test for get-mango-group with limit
(deftest test-get-mango-group-with-limit
  (let [db (pouchdb "testdb")]
    (async done
           (go
             ;; Query for type = "test" with limit 1
             (let [result (<! (get-mango-group db {:type "test"} 1))]
               (is (= 1 (count result))))
             (done)))))
