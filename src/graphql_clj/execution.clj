(ns graphql-clj.execution
  (:require [graphql-clj.schema-validator :as sv]
            [graphql-clj.query-validator :as qv]
            [graphql-clj.resolver :as resolver]
            [graphql-clj.error :as gerror]
            [clojure.set :as set]
            [clojure.string :as str]))

(declare collect-fields)

(defn- does-fragment-type-apply?
  "Implementation of DoesFragmentTypeApply(objectType, fragmentType)"
  [object-type fragment-type]
  true)

(defn collect-field-fn
  [type state]
  (fn [result selection]
    (prn "collect-field-fn: selection:" selection)
    (case (:tag selection)
      :selection-field (update result (:name selection) conj selection)
      :inline-fragment (when (does-fragment-type-apply? type (:on selection))
                         (let [fragment-grouped-field-set (collect-fields (:type selection) (:selection-set selection) {} state)]
                           (reduce (fn [result [name selection]]
                                     (println "result:" result "name:" name "selection:" selection)
                                     (update result name concat selection))
                                   result
                                   fragment-grouped-field-set))))))

(defn- collect-fields [type selection-set fields state]
  (reduce (collect-field-fn type state) fields selection-set))

(defn- get-field-type
  [schema parent-type-name field-name]
  (assert schema "Schema is nil!")
  (assert parent-type-name (format "parent-type-name is nil for field: %s!" parent-type-name))
  (assert field-name "field-name is nil!")
  (let [parent-type (get-in schema [:type-map parent-type-name])
        field-map (:field-map parent-type)
        field (get field-map field-name)
        field-type-name (get-in field [:type :name])
        field-type (get-in schema [:type-map field-type-name])]
    (assert parent-type (format "Could not found parent-type for type name: %s." parent-type-name))
    (case (get-in field [:type :tag])
      :basic-type (get-in schema [:type-map field-type-name])
      :list-type (:type field)
      (gerror/throw-error (format "Unhandled field type: %s (name = %s)." field field-name)))))

(defn- resolve-field-value
  "6.4.2 Value Resolution

  While nearly all of GraphQL execution can be described generically,
  ultimately the internal system exposing the GraphQL interface must
  provide values. This is exposed via ResolveFieldValue, which
  produces a value for a given field on a type for a real value."
  [{:keys [resolver-fn name arguments] :as field}
   {:keys [context resolver variables] :as state}
   parent-type-name parent-value]
  (let [field-name (:name field)
        resolver (or resolver-fn
                     (resolver (str parent-type-name) (str name)))
        default-arguments nil
        args nil]
    (resolver context parent-value args)))

(defn- complete-value
  "6.4.3 Value Completion

  After resolving the value for a field, it is completed by ensuring
  it adheres to the expected return type. If the return type is
  another Object type, then the field execution process continues
  recursively."
  [{:keys [selection-set name type required] :as field} {:keys [schema] :as state} result]
  (when (and required (nil? result))))

(defn- execute-field
  "Implement 6.4 Executing Field

  Each field requested in the grouped field set that is defined on the
  selected objectType will result in an entry in the response
  map. Field execution first coerces any provided argument values,
  then resolves a value for the field, and finally completes that
  value either by recursively executing another selection set or
  coercing a scalar value."
  [parent-type-name parent-value fields field-type state]
  (let [field (first fields)
        args nil ; FIXME
        resolved-value (resolve-field-value field state parent-type-name parent-value)]
    (complete-value field state resolved-value)))

(defn- execute-fields
  "Implements the 'Executing selection sets' section of the spec for 'read' mode."
  [fields state parent-type-name parent-value]
  (reduce (fn execute-fields-field [result-map [response-key response-fields]]
            (prn "execute-fields-field:" response-key)
            (prn "parent-type-name:" parent-type-name)
            (let [field-name (:name (first response-fields))
                  schema (:schema state)
                  field-type (get-field-type schema parent-type-name field-name)
                  response-value (execute-field parent-type-name parent-value fields field-type state)]
              (assoc result-map response-key response-value)))
          {}
          fields))

(defn- guard-missing-vars [variable-definitions vars]
  (let [required-var-names (->> (remove :default-value variable-definitions) (map :name) (map str) set)
        default-vars (->> (filter :default-value variable-definitions)
                          (map (fn [var-def]
                                 [(str (:name var-def))
                                  (get-in var-def [:default-value :value])]))
                          (into {}))
        input-var-names    (set (map key vars))
        missing-var-names  (set/difference required-var-names input-var-names)
        variables (merge default-vars vars)]
    {:errors (map (fn erorr-msg [name] {:message (format "Missing input variables (%s)." name)}) missing-var-names)
     :variables variables}))

(defn- get-operation-root-type
  "Extracts the root type of the operation from the schema."
  [{:keys [tag] :as operation} {:keys [schema] :as state}]
  (prn "schema:" schema)
  (case tag
    :query-definition (get-in schema [:roots :query])
    :selection-set (get-in schema [:roots :query])
    :mutation (get-in schema [:roots :mutation])
    {:errors [{:message "Can only execute queries, mutations and subscriptions"}]}))

(defn- execute-operation
  [{:keys [tag selection-set variable-definitions] :as operation} {:keys [variables schema] :as state}]
  (let [validation-result (guard-missing-vars variable-definitions variables)
        root-type (get-operation-root-type operation state)
        fields (collect-fields root-type selection-set {} state)]
    (prn "execute-operation: root-type:" root-type)
    (assert root-type "root-type is nil!")
    (if (seq (:errors validation-result))
      {:errors (:errors validation-result)}
      (case tag
        :query-definition (execute-fields fields state root-type :query-root-value)
        ;; anonymous default query
        :selection-set (execute-fields fields state root-type :query-root-value)
        ;; TODO: Execute fields serially
        :mutation (execute-fields fields state root-type :mutation-root-value)
        {:errors [{:message "Can only execute queries, mutations and subscriptions"}]}))))

(defn- execute-document
  [document state operation-name]
  ;; FIXME: Should only execute one statement per request, need
  ;; additional paramter to specify which statement will be
  ;; executed. Current implementation will merge result from multiple
  ;; statements.
  (println "document:" document)
  (let [operations (if operation-name
                     (filter #(= (:operation-name %) operation-name) document)
                     document)
        operation (first operations)
        operation-count (count operations)]
    (cond
      (= 1 operation-count) {:data (execute-operation operation state)}
      (= 0 operation-count) {:errors [{:message "Must provide an operation."}]}
      (> 1 operation-count) {:errors [{:message "Must provide operation name if query contains multiple operations."}]})))

;; Public API

(defn execute-validated-document
  ([context schema resolver-fn [statement-errors document] variables operation-name]
   (if (seq statement-errors)
     {:errors statement-errors}
     (execute-document document
                       {:variables (clojure.walk/stringify-keys variables)
                        :context context
                        :schema schema
                        :resolver (resolver/create-resolver-fn schema resolver-fn)}
                       operation-name)))
  ([context validated-schema resolver-fn validated-document]
   (execute-validated-document context validated-schema resolver-fn validated-document nil))
  ([context validated-schema resolver-fn validated-document variables]
   (execute-validated-document context validated-schema resolver-fn validated-document variables nil)))

(defn execute
  ([context string-or-validated-schema resolver-fn string-or-validated-document variables]
   (let [validated-schema (if (string? string-or-validated-schema)
                            (sv/validate-schema string-or-validated-schema)
                            string-or-validated-schema)
         validated-document (if (string? string-or-validated-document)
                              (try
                                (qv/validate-query validated-schema string-or-validated-document)
                                (catch Exception e
                                  [(:errors (ex-data e)) nil]))
                              string-or-validated-document)]
     (execute-validated-document context validated-schema resolver-fn validated-document variables)))
  ([context valid-schema resolver-fn string-or-validated-document]
   (execute context valid-schema resolver-fn string-or-validated-document nil)))


