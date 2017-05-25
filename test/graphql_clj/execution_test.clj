(ns graphql-clj.execution-test
  (:require [graphql-clj.execution :as sut]
            [clojure.test :as t :refer :all]
            [clojure.string :as str]))

(def simple-user-schema-str
  "type User {
  name: String
  nickname: String
  son: User
  friends: [User]
  phones: [String!]!
  cannotBeNull: String!
}

type QueryRoot {
  user: User
  loremIpsum(words: Int = 1): String!
  reqArg(arg:Int!): String
  stringList: [String]
  objectList: [User!]!
}

type CreateUser {
  id: String
  name: String
}

type MutationRoot {
  createUser(name: String = \"default user name\", required: Boolean!, optional: String): CreateUser
}

schema {
  query: QueryRoot
  mutation: MutationRoot
}")

(def user-resolver-fn
  "This resolver is schema / domain specific, implemented at the application level"
  (fn [type-name field-name]
    (get-in {"QueryRoot" {"user" (fn [& args]
                                   {:name     "Test user name"
                                    :nickname "Test user nickname"})
                          "loremIpsum" (fn [context parent args]
                                         (let [words (get args "words")]
                                           (str/join " " (repeat words "Lorem"))))
                          "reqArg" (fn [context parent args] (str (get args "arg")))
                          "stringList" (fn [_ _ _] ["0" "1" "2"])
                          "objectList" (fn [context parent args]
                                         (map (fn [no] {:name (format "Friend %s name" no)
                                                        :nickname (format "Friend %s nickname" no)})
                                              (range 2)))
                          }
             "User" {"son" (fn [context parent args]
                             {:name "Test son name"
                              :nickname "Son's nickname"})
                     "friends" (fn [context parent args]
                                 (map (fn [no] {:name (format "Friend %s name" no)
                                                :nickname (format "Friend %s nickname" no)})
                                      (range 5)))
                     "phones" (fn [context parent args]
                                (->> (range 3) (map str) vec))
                     }
             "MutationRoot" {"createUser" (fn [context parent arguments]
                                            {:id   (java.util.UUID/randomUUID)
                                             :name (get arguments "name")})
                             }}
            [type-name field-name])))

;; (def invalid-schema (create-test-schema borked-user-schema-str))
(def schema simple-user-schema-str)

(defn test-execute
  ([statement-str]
   (sut/execute nil schema user-resolver-fn statement-str))
  ([statement-str variables]
   (sut/execute nil schema user-resolver-fn statement-str variables)))

(deftest backwards-compatibility
  (testing "for unvalidated schemas entering the execution phase"
    (let [query "query {user {name}}"
          result (sut/execute nil simple-user-schema-str user-resolver-fn query)]
      (is (not (:errors result)))
      (is (= "Test user name" (get-in result [:data "user" "name"])))))
  (testing "for unvalidated statements entering the execution phase"
    (let [query "query {user {name}}"
          result (sut/execute nil schema user-resolver-fn query)]
      (is (not (:errors result)))
      (is (= "Test user name" (get-in result [:data "user" "name"]))))))

(deftest simple-execution
  (testing "simple execution"
    (let [result (test-execute "query {user {name}}")]
      (is (not (:errors result)))
      (is (= "Test user name" (get-in result [:data "user" "name"]))))))
