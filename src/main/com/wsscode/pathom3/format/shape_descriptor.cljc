(ns com.wsscode.pathom3.format.shape-descriptor
  "Shape descriptor is a format to describe data. This format optimizes for fast detection
  of value present given a shape and a value path.

  This namespace contains functions to operate on maps in the shape descriptor format."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [<- => >def >defn >fdef ? |]]
    [com.wsscode.misc.coll :as coll]
    [com.wsscode.misc.refs :as refs]
    [edn-query-language.core :as eql]))

(>def ::shape-descriptor
  "Describes the shape of a nested map using maps, this is a way to efficiently check
  for the presence of a specific path on data."
  (s/map-of any? ::shape-descriptor))

(defn merge-shapes
  "Deep merge of shapes, it takes in account that values are always maps."
  ([a] a)
  ([a b]
   (cond
     (and (map? a) (map? b))
     (merge-with merge-shapes a b)

     (map? a) a
     (map? b) b

     :else b)))

(defn data->shape-descriptor
  "Helper function to transform a map into an shape descriptor.

  Edges of shape descriptor are always an empty map. If a value of the map is a sequence.
  This will combine the keys present in all items on the final shape description.

  WARN: this idea of merging is still under test, this may change in the future."
  [data]
  (if (map? data)
    (reduce-kv
      (fn [out k v]
        (assoc out
          k
          (cond
            (map? v)
            (data->shape-descriptor v)

            (sequential? v)
            (let [shape (reduce
                          (fn [q x]
                            (coll/merge-grow q (data->shape-descriptor x)))
                          {}
                          v)]
              (if (seq shape)
                shape
                {}))

            :else
            {})))
      {}
      data)))

(>defn ast->shape-descriptor
  "Convert EQL AST to shape descriptor format."
  [ast]
  [:edn-query-language.ast/node => ::shape-descriptor]
  (reduce
    (fn [m {:keys [key type children] :as node}]
      (if (refs/kw-identical? :union type)
        (let [unions (into [] (map ast->shape-descriptor) children)]
          (reduce merge-shapes m unions))
        (assoc m key (ast->shape-descriptor node))))
    {}
    (:children ast)))

(>defn query->shape-descriptor
  "Convert pathom output format into shape descriptor format."
  [output]
  [:edn-query-language.core/query => ::shape-descriptor]
  (ast->shape-descriptor (eql/query->ast output)))

(>defn shape-descriptor->ast-children
  "Convert pathom output format into shape descriptor format."
  [shape]
  [::shape-descriptor => vector?]
  (into []
        (map (fn [[k v]]
               (if (seq v)
                 {:type         :join
                  :key          k
                  :dispatch-key k
                  :children     (shape-descriptor->ast-children v)}
                 {:type         :prop
                  :key          k
                  :dispatch-key k})))
        shape))

(>defn shape-descriptor->ast
  "Convert pathom output format into shape descriptor format."
  [shape]
  [::shape-descriptor => map?]
  {:type     :root
   :children (shape-descriptor->ast-children shape)})

(>defn shape-descriptor->query
  "Convert pathom output format into shape descriptor format."
  [shape]
  [::shape-descriptor => :edn-query-language.core/query]
  (into []
        (map (fn [[k v]]
               (if (seq v)
                 {k (shape-descriptor->query v)}
                 k)))
        shape))

(>defn missing
  "Given some available and required shapes, returns which items are missing from available
  in the required. Returns nil when nothing is missing."
  [available required]
  [::shape-descriptor ::shape-descriptor
   => (? ::shape-descriptor)]
  (let [res (into
              {}
              (keep (fn [el]
                      (let [attr      (key el)
                            sub-query (val el)]
                        (if (contains? available attr)
                          (if-let [sub-req (and (seq sub-query)
                                                (missing (get available attr) sub-query))]
                            (coll/make-map-entry attr sub-req))
                          el))))
              required)]
    (if (seq res) res)))

(>defn difference
  "Like set/difference, for shapes."
  [s1 s2]
  [(? ::shape-descriptor) (? ::shape-descriptor) => ::shape-descriptor]
  (reduce-kv
    (fn [out k sub]
      (if-let [x (find s2 k)]
        (let [v (val x)]
          (if (and (seq sub) (seq v))
            (let [sub-diff (difference sub v)]
              (if (seq sub-diff)
                (assoc out k sub-diff)
                out))
            out))
        (assoc out k sub)))
    (or (empty s1) {})
    s1))

(>defn select-shape
  "Select the parts of data covered by shape. This is similar to select-keys, but for
  nested shapes."
  [data shape]
  [map? ::shape-descriptor => map?]
  (reduce-kv
    (fn [out k sub]
      (if-let [x (find data k)]
        (let [v (val x)]
          (if (seq sub)
            (cond
              (map? v)
              (assoc out k (select-shape v sub))

              (or (sequential? v) (set? v))
              (assoc out k (into (empty v) (map #(select-shape % sub)) v))

              :else
              (assoc out k v))
            (assoc out k v)))
        out))
    (empty data)
    shape))
