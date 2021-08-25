# Hipflask: A Pouch for Rum

A ClojureScript atom interface to a PouchDB database.

Useful for making reactive apps that sync.

## Usage

```clojure
(def db (PouchDB "test"))
(.put db #js{:_id "doc" :number 1})
(def pa (patom db "doc"))
(go (println (<! (swap! pa update "number" inc))))
```
