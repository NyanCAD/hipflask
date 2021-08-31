(ns nyancad.hipflask
  (:require ["pouchdb" :as PouchDB]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :refer [starts-with?]]))

(defn pouchdb [name] (PouchDB. name))
(defn put [db doc] (.put db (clj->js doc)))
(defn bulkdocs [db docs] ^js (.bulkDocs db (clj->js docs)))
(defn alldocs [db options] ^js (.allDocs db options))

(defn update-keys
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(defn- docs-into [m ^js docs]
  (into m (map #(vector (get % "id") (get % "doc")))
        (js->clj (.-rows docs))))

(def sep "/")
(defn- get-group [db group]
  (let [docs (alldocs db #js{:include_docs true
                             :startkey (str group sep)
                             :endkey (str group sep "\ufff0")})]
    (go (docs-into {} (<p! docs)))))

(defn watch-changes [db group cachewr]
  (let [ch ^js (.changes db #js{:since "now"
                                :live true
                                :include_docs true
                                :filter #(starts-with? ^js (.-_id %) (str group sep))})]
    (.on ch "change" (fn [change]
                       (let [doc (js->clj ^js (.-doc change))
                             id (get doc "_id")]
                         (if-let [cache (.deref cachewr)]
                           (swap! cache assoc id doc)
                           (.cancel ch)))))))

(defn pouch-swap! [db cache f x & args]
  (letfn [(dissok [docs ^js doc] ; remove OK keys
            (if (.-ok doc)
              (dissoc docs (.-id doc))
              (do
                ;; (js/console.log "update error!" doc)
                docs)))
          (assok [cache doc] ; assoc OK documents revision into cache
            (let [id (.-id doc)
                  rev (.-rev doc)]
              (if rev
                (assoc-in cache [id "_rev"] rev)
                (dissoc cache id))))
          (keyset [key] ; the set of keys to update
            (cond
              (set? key) key ; update-keys
              (coll? key) #{(first key)} ; update-in
              :default #{key}))] ; update/assoc/dissoc/etc.
    (go-loop [docs (into {} (map (let [c @cache] #(vector % (get c %)))) (keyset x))]
      (let [updocs (apply f docs (if (set? x) (keys docs) x) args) ; apply f
            res (<p! (bulkdocs db (vals updocs))) ; try to put the updated docs
            ;400 errors get thrown, not returned
            errdocs (reduce dissok updocs res) ; remove the OK docs from the active set
            donedocs (reduce assok updocs res)] ; update the revisions of the OK docs in the cache
        ;; (println errdocs donedocs)
        (swap! cache into donedocs)
        (if (empty? errdocs)
          @cache
          (let [p (alldocs db (clj->js {:include_docs true :keys (keys errdocs)}))]
            (recur (docs-into {} (<p! p)))))))))

(deftype PAtom [db group cache init?]
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
   (watch-changes db group (js/WeakRef. cache))
   (PAtom. db group cache
           (go (reset! cache (<! (get-group db "group")))))))

(defn init? [^PAtom pa] (.-init? pa))