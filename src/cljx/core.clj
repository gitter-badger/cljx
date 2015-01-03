(ns cljx.core
  (:require [cljx.rules :as rules]
            [net.cgrand.sjacket :as sj]
            [net.cgrand.sjacket.parser :as p]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.zip :as z])
  (:import java.io.File
           java.net.URI))

(def ^:private warning-str ";;;;;;;;;;;; This file autogenerated from ")

;;Taken from clojure.tools.namespace
(defn- cljx-source-file?
  "Returns true if file is a normal file with a .cljx extension."
  [^File file]
  (and (.isFile file)
       (.endsWith (.getName file) ".cljx")))

(defn- find-cljx-sources-in-dir
  "Searches recursively under dir for CLJX files.
  Returns a sequence of File objects, in breadth-first sort order."
  [^File dir]
  ;; Use sort by absolute path to get breadth-first search.
  (let [files (file-seq (io/file dir))
        files (filter cljx-source-file? files)]
    (sort-by #(.getAbsolutePath %) files)))

(defn- walk
  [zloc {:keys [features transforms] :as rules}]
  (let [zloc (reduce #(%2 %) (rules/apply-features zloc features) transforms)]
    (if-not (z/branch? zloc)
      zloc
      (->> (z/down zloc)
           ; I feel like I've done this kind of zipper walk in a simpler way
           ; before...
           (iterate #(let [loc (walk % rules)]
                       (or (z/right loc) {::last loc})))
           (some ::last)
           z/up))))

(defn transform
  [code rules]
  (if (empty? (str/trim code))
    code
    (-> (p/parser code)
        (z/xml-zip)
        (walk rules)
        (z/root)
        (sj/str-pt))))

(defn- relativize
  "Get relative path of filepath in respect
  of rootpath."
  [filepath rootpath]
  (let [file-uri (.toURI (io/file filepath))
        dest-uri (.toURI (io/file rootpath))]
    (.getPath (.relativize dest-uri file-uri))))

(defn- destination-path
  [source source-path output-dir]
  (let [^String destpath (relativize source source-path)  ; Calculate relative path.
        ^URI destfile (io/file output-dir destpath)]      ; Create a dest file instance
    (.getAbsolutePath destfile)))

(defn generate
  "Generate source files from cljx files."
  ([options] (generate options (find-cljx-sources-in-dir (:source-path options))))
  ([{:keys [source-path output-path rules] :as options} files]
   (println "Rewriting" source-path "to" output-path
            (str "(" (:filetype rules) ")")
            "with features" (:features rules) "and"
            (count (:transforms rules)) "transformations.")
   (doseq [f files]
     (let [destpath (-> (destination-path f source-path output-path)
                        (str/replace #"\.[^\.]+$" (str "." (:filetype rules))))]
       ;; Create all directories if need
       (io/make-parents destpath)

       ;; Write the result
       (let [result (transform (slurp f) rules)
             result (with-out-str
                      (println result)
                      (print warning-str)
                      (println (.getPath f)))]
         (spit destpath result))))))

(defn cljx-compile
   "The actual static transform, separated out so it can be called repeatedly."
   [builds & {:keys [files]}]
   (doseq [{:keys [source-paths output-path rules] :as build} builds]
     (let [rules (cond
                   (= :clj rules) rules/clj-rules
                   (= :cljs rules) rules/cljs-rules
                   (symbol? rules) (do
                                     (require (symbol (namespace rules)))
                                     @(resolve rules))
                   :default (eval rules))]
       (doseq [p source-paths]
         (if files
           (generate (assoc build :rules rules :source-path p) files)
           (generate (assoc build :rules rules :source-path p)))))))
