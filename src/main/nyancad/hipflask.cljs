(ns nyancad.hipflask
  (:require ["pouchdb" :as PouchDB]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :refer [starts-with?]]))

(defn pouchdb [name] (PouchDB. name))
(defn put [db doc] (.put db (clj->js doc)))

(defn update-keys
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(def sep "/")
(defn get-group [db group]
  (go (into {} (map #(vector (keyword (:id %)) (:doc %)))
             (js->clj (.-rows
                       (<p! (.allDocs db #js{:include_docs true
                                             :startkey (str group sep)
                                             :endkey (str group sep "\ufff0")})))
              
              :keywordize-keys true))))

(defn watch-changes [db group cachewr]
  (let [ch (.changes db #js{:since "now"
                            :live true
                            :include_docs true
                            :filter #(starts-with? (.-_id %) (str group sep))
                            })]
    (.on ch "change" (fn [change]
                       (let [doc (js->clj (.-doc change) :keywordize-keys true)
                             id (keyword (:_id doc))]
                         (if-let [cache (.deref cachewr)]
                           (swap! cache assoc id doc)
                           (.cancel ch)))))))

(defn pouch-swap! [db cache f x & args]
  (letfn [(disjok [keys ^js doc]
            (if (.-ok doc)
              (disj keys (keyword (.-id doc)))
              (do
                (js/console.log doc)
                keys)))
          (assok [cache doc]
            (let [id (keyword (.-id doc))
                  rev (.-rev doc)]
              (if rev
                (assoc-in cache [id :_rev] rev)
                cache)))
          (keyset [key]
            (cond
              (set? key) key
              (coll? key) #{(first key)}
              :default #{key}))]
    (go-loop [old @cache
              keys x]
      (let [updated (apply f old keys args)
            updocs (map #(get updated %) (keyset keys))
            res (<p! (.bulkDocs db (clj->js updocs))) ; 400 errors get thrown, not returned
            nextkeys (reduce disjok (keyset keys) res)
            updated (reduce assok updated res)]
        (if (empty? nextkeys)
          (reset! cache updated)
          (recur updated (if (set? keys) nextkeys keys)))))))

(deftype PAtom [db group cache]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]          (throw (js/Error "Pouch atom assumes first argument is a key")))
  (-swap! [_a f x]        (pouch-swap! db cache f x))
  (-swap! [_a f x y]      (pouch-swap! db cache f x y))
  (-swap! [_a f x y more] (apply pouch-swap! db cache f x y more)))

(defn pouch-atom
  ([db group] (pouch-atom db group (atom {})))
  ([db group cache]
  (let [p (PAtom. db group cache)]
    (go (reset! cache (<! (get-group db "group"))))
    (watch-changes db group (js/WeakRef. p))
    p)))