# Hipflask: A Pouch for Rum

A ClojureScript atom interface to a PouchDB database.

Useful for making reactive [rum](https://github.com/tonsky/rum) apps that sync.

## Usage

```clojure
(def db (pouchdb "test"))
(put db {:_id "group/doc1" :number 1})
(put db {:_id "group/doc2" :number 1})
(def pa (pouch-atom db "group"))
(go (println (<!
  (swap! pa update-keys #{"group/doc1" "group/doc2"} update "number" inc))))
```

With Reagent, make sure to use a ratom as the cache.
```clojure
(pouch-atom db "group" (r/atom {}))
```

## Examples

* [Global Cookie Clicker](http://wishfulcoding.nl/gcc/): `examples/gcc`
