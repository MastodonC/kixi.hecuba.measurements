(ns kixi.hecuba.dcc.measurements.processor
  (:gen-class)
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as cstr]
            [environ.core :refer [env]]
            [franzy.admin.cluster :as cluster]
            [franzy.admin.zookeeper
             [client :as client]
             [defaults :as zk-defaults]]
            [kixi.hecuba.dcc.measurements
             [hecuba-api :refer [post-measurements get-entities]]
             [parser :refer [process]]]
            [taoensso.timbre :as log]
            [com.rpl.specter :as specter])
  (:import org.apache.kafka.common.serialization.Serdes
           [org.apache.kafka.streams KafkaStreams StreamsConfig]
           [org.apache.kafka.streams.kstream KStreamBuilder Predicate ValueMapper]))

(defn config [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile}))

(defn get-broker-list
  [zk-conf]
  (let [c (merge (zk-defaults/zk-client-defaults) zk-conf)]
    (with-open[u (client/make-zk-utils c false)]
      (cluster/all-brokers u))))

(defn broker-str [zkconf]
  (let [zk-brokers (get-broker-list zkconf)
        brokers (map (fn [broker] (str (get-in broker [:endpoints :plaintext :host]) ":" (get-in broker [:endpoints :plaintext :port])) ) zk-brokers)]
    (if (= 1 (count brokers))
      (first brokers)
      (cstr/join "," brokers))))

(defn deserialize-message [bytes]
  (try (-> bytes
           java.io.ByteArrayInputStream.
           io/reader
           slurp)
       (catch Exception e (log/info (.printStackTrace e)))
       (finally (log/info ""))))


(defn get-entity-and-devices
  "only to be used until we implement a correspondence from correlation id"
  [hecuba correlation-id device-type]
  (let [response (get-entities hecuba correlation-id 10)
        meter-type (get {"electricityConsumption" "electricityMeter"
                         "gasConsumption" "gasMeter"} device-type)]
    (log/info "meter-type" meter-type)
    {:entity-id (:entity_id (first response))
     :device-id (specter/select-one [:devices
                                     specter/ALL
                                     #(= (:description %) meter-type)
                                     :device_id
                                     ] (first response))}))

(defn parse-data
  "parse xml and transform to JSON for the hecuba api, then POST to hecuba"
  [data-in]
  (-> data-in
      deserialize-message
      process))

(defn pre-process-data
  "parse xml and transform to JSON for the hecuba api, then POST to hecuba"
  [hecuba data-in]
  (let [{:keys [measurements correlation-id]} (parse-data data-in)
        device-type (-> measurements first :type)
        devices (get-entity-and-devices hecuba correlation-id device-type)]
    ;; TODO: look up entity-id and/or devide-id
    (when (or (nil? (:entity-id devices))
              (nil? (:device-id devices)))
      (do (log/error "Device or Entity for this correlation ID not found!" correlation-id)
          (throw (Exception. (str "Device or Entity for this correlation ID not found:" correlation-id)))))))

(defn process-data
  "parse xml and transform to JSON for the hecuba api, then POST to hecuba"
  [hecuba data-in]
  (let [{:keys [measurements correlation-id]} (parse-data data-in)
        device-type (-> measurements first :type)
        devices (get-entity-and-devices hecuba correlation-id device-type)]
    ;; TODO: look up entity-id and/or devide-id
    (if (or (nil? (:entity-id devices))
            (nil? (:device-id devices)))
      (do (log/error "Device or Entity for this correlation ID not found!" correlation-id)
          (throw (Exception. (str "Device or Entity for this correlation ID not found:" correlation-id))))
      (post-measurements hecuba measurements (:entity-id devices) (:device-id devices)))))

(defn start-stream []
  (let [{:keys [hecuba kafka zookeeper] :as configuration} (config (keyword (env :profile)))
        _ (log/info "PROFILE" (env :profile))
        _ (log/info "CONFIGURATION" (dissoc configuration :hecuba))
        broker-list (broker-str {:servers zookeeper})
        props {StreamsConfig/APPLICATION_ID_CONFIG,  (:consumer-group kafka)
               StreamsConfig/BOOTSTRAP_SERVERS_CONFIG, broker-list
               StreamsConfig/ZOOKEEPER_CONNECT_CONFIG, zookeeper
               StreamsConfig/TIMESTAMP_EXTRACTOR_CLASS_CONFIG "org.apache.kafka.streams.processor.WallclockTimestampExtractor"
               StreamsConfig/KEY_SERDE_CLASS_CONFIG,   (.getName (.getClass (Serdes/String)))
               StreamsConfig/VALUE_SERDE_CLASS_CONFIG, (.getName (.getClass (Serdes/ByteArray)))}
        builder (KStreamBuilder.)
        config (StreamsConfig. props)
        input-topic (into-array String [(:topic kafka)])
        dead-letter-topic-name (:dead-letter kafka)
        dead-letter-topic (into-array String [dead-letter-topic-name])]
    (log/infof "Zookeeper Address: %s" zookeeper)
    (log/infof "Broker List: %s" broker-list)
    (log/infof "Kafka Topic: %s" (:topic kafka))
    (log/infof "Kafka Consumer Group: %s" (:consumer-group kafka))
    (do (let [partitioned-stream (.branch (.stream builder input-topic)
                                          (into-array Predicate [(reify Predicate (test [_ _ v] (try (do (pre-process-data hecuba v)
                                                                                                         true)
                                                                                                     (catch Throwable t
                                                                                                       (do (log/error t)
                                                                                                           false)))))
                                                                 (reify Predicate (test [_ _ _] true))]))
              dead-letter-topic-stream (.stream builder dead-letter-topic)]
          (-> (aget partitioned-stream 0)
              (.mapValues (reify ValueMapper (apply [_ v] (process-data hecuba v))))
              (.print))
          (-> (aget partitioned-stream 1)
              (.to dead-letter-topic-name)))
        (KafkaStreams. builder config))))
