(ns hooks.jolt
  (:require [clj-kondo.hooks-api :as api]))

(defn defcfn
  "Teach clj-kondo jolt.ffi/defcfn:
     (ffi/defcfn name \"c-name\" [:type ...] :ret-type {:blocking true}?)
   defines a var `name` holding an FFI function whose arity is the
   count of the type vector (the entries are FFI type specs, not
   bindings). A :& entry in the vector marks C varargs: the params
   before it are fixed, the rest collect. Rewrite to
   (def name (fn [_arg0 ...] (throw ...))) so the var registers,
   call sites arity-check, and the return type stays unknown (a
   literal body would poison every caller's type analysis)."
  [{:keys [node]}]
  (let [[_ name-node _cname-node args-node] (:children node)
        types (api/sexpr args-node)
        variadic? (boolean (some #(= :& %) types))
        fixed (if variadic? (take-while #(not= :& %) types) types)
        params (mapv (fn [i] (api/token-node (symbol (str "_arg" i)))) (range (count fixed)))
        params (if variadic?
                 (conj params (api/token-node '&) (api/token-node '_rest))
                 params)
        new-node (api/list-node
                  [(api/token-node 'def)
                   name-node
                   (api/list-node
                    [(api/token-node 'fn)
                     (api/vector-node params)
                     (api/list-node [(api/token-node 'throw)
                                     (api/list-node [(api/token-node 'ex-info)
                                                     (api/string-node "")
                                                     (api/map-node [])])])])])]
    {:node new-node}))
