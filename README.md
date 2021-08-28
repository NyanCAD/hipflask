# Hipflask: A Pouch for Rum

A ClojureScript atom interface to a PouchDB database.

Useful for making reactive [rum](https://github.com/tonsky/rum) apps that sync.

## Usage

```clojure
(def db (pouchdb "test"))
(put db {:_id "doc" :number 1})
(def pa (patom db "doc"))
(go (println (<! (swap! pa update "number" inc))))
```
