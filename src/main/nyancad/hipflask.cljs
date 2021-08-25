(ns nyancad.hipflask
  (:require ["pouchdb" :as PouchDB]
            [cljs.core.async :refer [go go-loop <!]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn errorcode [^js err]
  (.log js/console err)
  (.-status (.-cause err)))

(defn pouch-swap! [db key validator f & args]
  (go-loop []
    (let [old (js->clj (<p! (.get db key)))
          updated (apply f old args)
          res (try
                (when-not (nil? validator)
                  (assert (validator updated)))
                (<p! (.put db (clj->js updated)))
                (catch :default err err))]
      (if (instance? js/Error res)
        (if (= 409 (errorcode res))
          (recur)
          (throw res))
        updated)))) 

(deftype PAtom [db key validator ^:mutable watches]
  IAtom

  IDeref
  (-deref [_this]
    (go (js->clj (<p! (.get db key)))))

  ISwap
  (-swap! [_a f]          (pouch-swap! db key validator f))
  (-swap! [_a f x]        (pouch-swap! db key validator f x))
  (-swap! [_a f x y]      (pouch-swap! db key validator f x y))
  (-swap! [_a f x y more] (apply pouch-swap! db key validator f x y more))

  IWatchable
  (-notify-watches [this old new] (doseq [[k f] watches] (f k this old new)))
  (-add-watch [_this key f] (set! watches (assoc watches key f)))
  (-remove-watch [_this key] (set! watches (dissoc watches key))))

(defn patom [db key]
  (let [p (PAtom. db key nil nil)]
    (-> db
        (.changes #js{:since "now"
                      :live true
                      :include_docs true
                      :doc_ids #js[key]})
        (.on "change" (fn [change] (-notify-watches p nil (js->clj (.-doc change))))))
    p))