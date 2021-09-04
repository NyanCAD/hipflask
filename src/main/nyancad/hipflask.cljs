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
  "Apply f to every valen in keys"
  ([m keys f] (into m (map #(vector % (f (get m %)))) keys))
  ([m keys f & args] (into m (map #(vector % (apply f (get m %) args))) keys)))

(defn- tombstone-conj [m [k v]]
  (if (nil? v)
    (dissoc m k)
    (assoc m k v)))

(defn- tombstone-conj! [m [k v]]
  (if (nil? v)
    (dissoc! m k)
    (assoc! m k v)))

; like into, but dissoc's nil values
(defn- tombstone-into
  ([] [])
  ([to] to)
  ([to from]
   (if (implements? IEditableCollection to)
     (persistent! (reduce tombstone-conj! (transient to) from))
     (reduce tombstone-conj to from)))
  ([to xform from]
   (if (implements? IEditableCollection to)
     (persistent! (transduce xform tombstone-conj! (transient to) from))
     (transduce xform tombstone-conj to from))))

(defn- docs-into [m ^js docs]
  (into m (map #(vector (get % "id") (get % "doc")))
        (js->clj (.-rows docs))))

(def sep ":")
(defn- get-group [db group]
  (let [docs (alldocs db #js{:include_docs true
                             :startkey (str group sep)
                             :endkey (str group sep "\ufff0")})]
    (go (docs-into {} (<p! docs)))))

(defn- watch-changes [db group cachewr]
  (let [ch ^js (.changes db #js{:since "now"
                                :live true
                                :include_docs true
                                :filter #(starts-with? ^js (.-_id %) (str group sep))})]
    (.on ch "change" (fn [change]
                       (let [doc (js->clj ^js (.-doc change))
                             id (get doc "_id")
                             del? (get doc "_deleted")]
                         (if-let [cache (.deref cachewr)]
                           (if del?
                             (swap! cache dissoc id)
                             (swap! cache assoc id doc))
                           (.cancel ch)))))))

(defn- pouch-swap! [db cache f x & args]
  (letfn [(prepare [old new] ; get the new values, filling in _id, _ref, _deleted
            (for [[id {rev "_rev"}] old]
              (if-let [newdoc (get new id)]
                (if rev
                  (assoc newdoc "_id" id "_rev" rev) ; make sure new value has correct id and rev
                  (assoc newdoc "_id" id)) ; new doc, add id
                {"_id" id "_rev" rev "_deleted" true}))) ; deleted
          (dissok [docs ^js doc] ; remove OK documents (finished)
            (if (.-ok doc)
              (dissoc docs (.-id doc))
              (do
                ;; (js/console.log "update error!" doc)
                docs)))
          (assok [cache doc] ; proccess OK (finished) documents
            (let [id (.-id doc)
                  rev (.-rev doc)]
              (if rev
                (if (contains? cache id)
                  (assoc-in cache [id "_rev"] rev) ;done, update rev
                  (assoc cache id nil)) ; delete, assoc nil so we can delete later
                (dissoc cache id)))) ; conflict, remove
          (keyset [key] ; the set of keys to update
            (cond
              (set? key) key ; update-keys
              (coll? key) #{(first key)} ; update-in
              :default #{key}))] ; update/assoc/dissoc/etc.
    ; build a map with only the keys to update
    (go-loop [docs (into {} (map (let [c @cache] #(vector % (get c %)))) (keyset x))]
      (let [updocs (apply f docs (if (set? x) (keys docs) x) args)] ; apply f
        (if (= docs updocs)
          @cache ; nothing changed, no need to do anything
          (let [prepdocs (prepare docs updocs) ; prepare for sending to PouchDB
                res (<p! (bulkdocs db prepdocs)) ; try to put the updated docs
                ;400 errors get thrown, not returned
                errdocs (reduce dissok updocs res) ; remove the OK docs from the active set
                donedocs (reduce assok updocs res)] ; update the revisions of the OK docs
            ; update OK docs in the cach, we're done with them
            ; we use tombstone-into here, so that nil values from assok get removed from the cache
            (swap! cache tombstone-into donedocs)
            (if (empty? errdocs) ; are there any conflicst left to retry?
              @cache ; done
              ; fetch the latest documents from the DB for all conflicts
              (let [p (alldocs db (clj->js {:include_docs true :keys (keys errdocs)}))]
                (recur (docs-into {} (<p! p))))))))))) ; recur with remaining documents

(deftype PAtom [db group cache init?]
  IAtom

  IDeref
  (-deref [_this] @cache)

  ISwap
  (-swap! [_a _f]         (throw (js/Error "Pouch atom assumes first argument is a key")))
  (-swap! [_a f x]        (pouch-swap! db cache f x))
  (-swap! [_a f x y]      (pouch-swap! db cache f x y))
  (-swap! [_a f x y more] (apply pouch-swap! db cache f x y more))

  IWatchable
  (-notify-watches [_this old new] (-notify-watches cache old new))
  (-add-watch [this key f]         (-add-watch cache key (fn [key _ old new] (f key this old new))))
  (-remove-watch [_this key]       (-remove-watch cache key)))

(defn pouch-atom
  ([db group] (pouch-atom db group (atom {})))
  ([db group cache]
   (watch-changes db group (js/WeakRef. cache))
   (PAtom. db group cache
           (go (reset! cache (<! (get-group db group)))))))

(defn init? [^PAtom pa] (.-init? pa))