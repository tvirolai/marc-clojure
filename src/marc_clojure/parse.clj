(ns marc-clojure.parse
  (:require [clojure.string :as s]))

(defn iterator->lazyseq
  "This function transforms a Java iterable into a Clojure lazy sequence"
  [iterable]
  (lazy-seq
    (reify java.lang.Iterable
      (iterator [this]
        (reify java.util.Iterator
          (hasNext [this] (.hasNext iterable))
          (next [this] (.next iterable))
          (remove [this] (.remove iterable)))))))

(defn- datafield? [tagstring]
  (let [tag-number (try (Integer. tagstring)
                        (catch Exception e
                          (println (str "Non-numeral field: " tagstring))
                          999))]
    (if (> tag-number 9) true false)))

(defn- get-id [record]
  (->> (.getVariableField record "001")
       .getData
       s/trim))

(defn serialize-subfield [subfield]
  {:code (str (.getCode subfield))
   :data (.getData subfield)})

(defn- parse-subfields [fieldcontent]
  (->> (s/split fieldcontent #"\$")
       (filter not-empty)
       (map #(hash-map :code (subs % 0 1)
                       :data (subs % 1)))
       vec))

(defn serialize-field [fieldstring]
  (let [tag (subs fieldstring 0 3)]
    (if-not (datafield? tag)
      {:type "controlfield"
       :tag tag
       :data (subs fieldstring 4)}
      {:type "datafield"
       :tag tag
       :i1 (subs fieldstring 4 5)
       :i2 (subs fieldstring 5 6)
       :subfields (parse-subfields (subs fieldstring 6))})))

(defn serialize-record [recstring]
  (let [elements (s/split recstring #"\n")
        leader (first elements)
        fields (->> elements rest (map serialize-field) vec)]
    {:bibid (->> fields (filter #(= "001" (:tag %))) first :data s/trim)
     :leader (subs leader 7)
     :fields fields}))

;; Parse data in Aleph sequential format

(def data "./data/testidata.seq")

(defn- marc-boundary?
  [line]
  (not (nil? (re-matches #"^[0-9]{9} FMT.*" line))))

(defn get-tag-aleph [line]
  (subs line 10 13))

(defn get-content-aleph [line]
  (subs line 18))

(defn get-i1-aleph [line]
  (subs line 13 14))

(defn get-i2-aleph [line]
  (subs line 14 15))

(defn- aleph-internal-field? [line]
  (not (empty? (re-seq #"[A-Z]" (get-tag-aleph line)))))

(defn remove-leading-zeros [string]
  (->> string Integer. str))

(defn parse-subfields-aleph
  "Takes a line of Aleph Sequential data, returns a vector of subfields."
  [line]
  (let [sfs (-> line get-content-aleph (s/split #"\$\$") rest)]
    (->> sfs
         (map #(hash-map :code (subs % 0 1)
                         :data (subs % 1)))
         vec)))

(defn parse-field-aleph [line]
  (let [tag (subs line 10 13)
        content (subs line 18)]
    (if-not (datafield? tag)
      {:type "controlfield"
       :tag (get-tag-aleph line)
       :content (get-content-aleph line)}
      {:type "datafield"
       :i1 (get-i1-aleph line)
       :i2 (get-i2-aleph line)
       :tag (get-tag-aleph line)
       :content (parse-subfields-aleph line)})))

(defn serialize-record-aleph [data]
  (let [ldr (->> data (filter #(= "LDR" (get-tag-aleph %))) first get-content-aleph)
        content (->> data (remove aleph-internal-field?))
        id (->> content (filter #(= "001" (get-tag-aleph %))) first get-content-aleph remove-leading-zeros)]
    {:bibid id
     :leader ldr
     :fields (vec (map parse-field-aleph content))}))

(defmulti load-data (fn [format & _] format))

(defmethod load-data :marc [_ filename]
  (let [recseq (-> filename java.io.FileInputStream. org.marc4j.MarcStreamReader. iterator->lazyseq)]
    (pmap (comp serialize-record #(.toString %)) recseq)))

(defmethod load-data :marcxml [_ filename]
  (let [recseq (-> filename java.io.FileInputStream. org.marc4j.MarcXmlReader. iterator->lazyseq)]
    (pmap (comp serialize-record #(.toString %)) recseq)))

(defmethod load-data :aleph [_ filename]
  (let [lst (line-seq (clojure.java.io/reader filename))
        parts (partition-by marc-boundary? lst)
        records (map flatten (partition 2 parts))]
    (map serialize-record-aleph records)))