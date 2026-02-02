<!--
SPDX-FileCopyrightText: 2022 Pepijn de Vos

SPDX-License-Identifier: MPL-2.0
-->

# Hipflask: A Pouch for Rum

A ClojureScript atom interface to PouchDB/CouchDB for building offline-first collaborative applications.

Hipflask transparently synchronizes application state between your UI and database, with automatic conflict resolution through retries. Works seamlessly with atom-based frameworks like [Reagent](https://reagent-project.github.io/) and [Rum](https://github.com/tonsky/rum).

## Philosophy

Unlike CRDTs which promise automatic conflict-free merging, hipflask takes a different approach: **application-specific conflict resolution**. As discussed in [The Limits of Conflict-Free Replicated Data Types](https://pepijndevos.nl/2021/12/18/the-limits-of-conflict-free-replicated-data-types.html), generic conflict-free structures can still produce semantically nonsensical results when concurrent edits are merged.

Hipflask embraces CouchDB's optimistic concurrency model:
1. Apply your changes optimistically
2. If a conflict occurs, fetch the latest state and retry
3. Your update function runs again with fresh data, resolving conflicts through your application logic

This means your `swap!` function determines how conflicts are resolved - incrementing a counter will correctly accumulate all increments, while replacing a value will use the last write.

### Important Considerations

**Atomicity is per-document, not per-atom.** Each key in the atom corresponds to a separate CouchDB document. Concurrent updates to different keys always succeed independently, while concurrent updates to the *same* key trigger the retry mechanism. This means update functions must be idempotent on a per-key basis, as each key may be retried independently.

**Awaiting is optional.** You don't have to wait for `done?` if your UI gracefully handles an initially empty state that fills in asynchronously. Similarly, you don't have to await `swap!` calls unless you need sequential operations or want to catch validation errors (rejected writes are logged to the console).

## Features

- **Atom interface** - Use familiar `swap!`, `deref`, and `add-watch`
- **Automatic sync** - Changes propagate to/from PouchDB automatically
- **Conflict resolution** - Automatic retry on conflicts with your update function
- **Flexible storage** - Local-only (PouchDB), remote-only (CouchDB), or synced
- **Framework agnostic** - Works with Reagent ratoms, Rum, or plain atoms
- **Validators** - Pre-commit validation to reject invalid state changes
- **Document groups** - Organize documents by prefix for efficient queries

## Installation

Add to your `deps.edn` or `project.clj`:

```clojure
;; deps.edn
{:deps {org.clojars.pepijndevos/hipflask {:mvn/version "0.10.5"}}}

;; project.clj
[org.clojars.pepijndevos/hipflask "0.10.5"]
```

## Quick Start

```clojure
(ns myapp.core
  (:require [nyancad.hipflask :refer [pouchdb put pouch-atom watch-changes done?]]
            [cljs.core.async :refer [go <!]]))

;; Create a database (local PouchDB)
(def db (pouchdb "myapp"))

;; For remote CouchDB:
;; (def db (pouchdb "http://localhost:5984/myapp"))

;; Create a pouch-atom for a document group
;; Documents are grouped by prefix (e.g., "todos:item1", "todos:item2")
(def todos (pouch-atom db "todos"))

;; Watch for remote changes
(watch-changes db todos)

;; Optional: wait for initial load (or let UI handle empty state)
(go
  (<! (done? todos))
  (println "Loaded:" @todos))

;; Use like a normal atom - awaiting is optional for non-sequential ops
(swap! todos assoc "todos:1" {:text "Buy milk" :done false})

;; Await only if you need sequential operations or to catch errors
(go
  (<! (swap! todos assoc "todos:2" {:text "Buy eggs" :done false}))

  ;; Update a document
  (<! (swap! todos update-in ["todos:1" :done] not))

  ;; Read current state
  (println @todos))
```

## Usage with Reagent

Pass a Reagent ratom as the cache to get reactive updates:

```clojure
(ns myapp.core
  (:require [reagent.core :as r]
            [nyancad.hipflask :refer [pouchdb pouch-atom watch-changes]]))

(def db (pouchdb "myapp"))
(def todos (pouch-atom db "todos" (r/atom {})))
(watch-changes db todos)

(defn todo-list []
  [:ul
   (for [[id {:keys [text done]}] @todos]
     ^{:key id}
     [:li {:class (when done "done")} text])])
```

## Usage with Rum

```clojure
(ns myapp.core
  (:require [rum.core :as rum]
            [nyancad.hipflask :refer [pouchdb pouch-atom watch-changes]]))

(def db (pouchdb "myapp"))
(def todos (pouch-atom db "todos"))
(watch-changes db todos)

(rum/defc todo-list < rum/reactive []
  [:ul
   (for [[id {:keys [text done]}] (rum/react todos)]
     [:li {:key id :class (when done "done")} text])])
```

## API Reference

### Database

- `(pouchdb name)` - Create/open a PouchDB database. Use a URL for remote CouchDB.
- `(put db doc)` - Put a single document (must have `:_id`)
- `(watch-changes db & patoms)` - Watch for remote changes and update atoms

### Pouch Atom

- `(pouch-atom db group)` - Create an atom for documents with the given prefix
- `(pouch-atom db group cache)` - Use a custom atom (e.g., Reagent ratom)
- `(done? patom)` - Returns a channel that closes when initial load completes
- `(add-watch-group groups patom)` - Add another atom to an existing change watcher

### Swapping

The first argument to `swap!` must be either:
- A key (string) - for `assoc`, `update`, `dissoc`
- A set of keys - for `update-keys`
- A map - for `into`

```clojure
;; Single document operations
(swap! pa assoc "group:id" {:data "value"})
(swap! pa update "group:id" merge {:more "data"})
(swap! pa dissoc "group:id")  ; deletes document

;; Bulk operations
(swap! pa into {"group:a" {:n 1} "group:b" {:n 2}})
(swap! pa update-keys #{"group:a" "group:b"} update :n inc)
```

### Querying

- `(get-group db group)` - Get all documents in a group
- `(get-group db group limit)` - Get documents with limit
- `(get-group db group limit target)` - Specify target collection type
- `(get-view-group db view prefix)` - Query a CouchDB view
- `(get-mango-group db selector)` - Query using Mango/PouchDB-find

### Validation

Set a validator to reject invalid state changes before they're persisted:

```clojure
(def pa (pouch-atom db "items"))

;; Validator receives the map of changed documents
(set! (.-validator pa)
  (fn [docs]
    (every? (fn [[id doc]]
              (or (nil? doc)  ; deletions ok
                  (pos? (:quantity doc))))
            docs)))

;; This will throw if quantity is not positive
(swap! pa assoc "items:1" {:quantity -5})
```

## Sync Configuration

```clojure
;; Local only (offline, no sync)
(def local-db (pouchdb "local-only"))

;; Remote only (online, direct CouchDB access)
(def remote-db (pouchdb "http://couch.example.com/mydb"))

;; Synced (offline-first with background sync)
(def local-db (pouchdb "myapp"))
(def remote-db (pouchdb "http://couch.example.com/mydb"))
(.sync local-db remote-db #js{:live true :retry true})
```

## Examples

- [Global Cookie Clicker](http://wishfulcoding.nl/gcc/): A collaborative cookie clicker (`examples/gcc`)
- [NyanCAD Mosaic](https://nyancad.com): Collaborative analog circuit design powered by hipflask

## License

MPL-2.0
