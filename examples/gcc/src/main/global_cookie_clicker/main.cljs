(ns global-cookie-clicker.main
  (:require [reagent.core :as r]
            [reagent.dom :as rd]
            [nyancad.hipflask :as hf]
            clojure.set))

(def clicker-price 50)

(def db (hf/pouchdb "cookies"))
(def me (if-let [uuid (js/localStorage.getItem "user")]
          uuid
          (let [uuid (str (random-uuid))]
            (js/localStorage.setItem "user" uuid)
            uuid)))

(def rginger (r/atom {}))
(def ginger (hf/pouch-atom db "gingerbread" rginger))

(def rchoco (r/atom {}))
(def choco (hf/pouch-atom db "chocolate" rchoco))

(defn count-global [a type]
  (reduce #(+ %1 (get %2 type)) 0 (vals @a)))

(defn count-mine [a type]
  (get-in @a [(str (.-group a) hf/sep me) type] 0))

(defn make-cookie [a n _]
  (swap! a update-in [(str (.-group a) hf/sep me) "cookies"] #(+ % n)))

(defn buy-clicker [a _]
  (swap! a (fn [doc key]
             (let [cookies (get-in doc [key "cookies"] 0)
                   clickers (get-in doc [key "clickers"] 0)]
               (if (>= cookies clicker-price)
                 (update doc key assoc
                         "cookies" (- cookies clicker-price)
                         "clickers" (inc clickers))
                 doc)))
         (str (.-group a) hf/sep me)))

(defn do-clickers []
  (doseq [a [choco ginger]]
    (make-cookie a (count-mine a "clickers") nil)))

(defn do-sync []
  (.sync db "https://c6be5bcc-59a8-492d-91fd-59acc17fef02-bluemix.cloudantnosqldb.appdomain.cloud/cookies"))

(defonce clicker (js/setInterval do-clickers 1000))
(defonce syncer (js/setInterval do-sync 5964))

(defn main []
  [:div
   [:p "You have " (count-mine choco "cookies") " of the global "(count-global choco "cookies") " chocolate cookies"]
   [:p "You have " (count-mine choco "clickers") " of the global "(count-global choco "clickers") " chocolate clickers"]
   [:p "You have " (count-mine ginger "cookies") " of the global "(count-global ginger "cookies") " gingerbread cookies"]
   [:p "You have " (count-mine ginger "clickers") " of the global "(count-global ginger "clickers") " gingerbread clickers"]
   [:button {:on-click #(make-cookie choco 1 %)} "Chocolate cookie!"]
   [:button {:on-click #(buy-clicker choco %)} "Chocolate clicker! (50)"]
   [:button {:on-click #(make-cookie ginger 1 %)} "Gingerbread cookie!"]
   [:button {:on-click #(buy-clicker ginger %)} "Gingerbread clicker! (50)"]])

(defn ^:dev/after-load init []
  (rd/render [main]
             (.getElementById js/document "root")))