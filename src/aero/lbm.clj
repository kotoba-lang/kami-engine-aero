(ns aero.lbm
  "High-fidelity aero backend — registers `:lbm` on the shared cae-solver
  contract by shelling out to the clean-room `kami-cfd` (Rust D2Q9
  lattice-Boltzmann) binary. So the SAME aero case answered by `:rom-buildup`
  (aero.solver) can instead be answered by a resolved-flow LBM, caller
  unchanged: (cae.solver/solve (assoc case :solver {:kind :lbm})).

  JVM-only (uses clojure.java.shell). The binary is located via the
  KAMI_CFD_BIN env var, else the sibling west-project build path. kami-cfd
  returns a 2D *sectional* drag coefficient; we apply a documented calibration
  constant to map it to a vehicle-scale Cd (full 3D Cd needs the D3Q19
  extension — calibration is the honest interim bridge)."
  (:require [cae.solver :as cae]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; sectional Cd (block ≈ 3.06 @Re100) → vehicle Cd; anchored so a sedan block
;; maps to ≈ the rom-buildup reference (0.248). Retune against CFD/tunnel data.
(def ^:const calibration 0.081)

(defn- resolve-bin []
  (or (System/getenv "KAMI_CFD_BIN")
      (->> ["../kami-cfd/target/aarch64-apple-darwin/release/kami-cfd"
            "../kami-cfd/target/release/kami-cfd"
            "kami-cfd"]
           (filter #(or (= % "kami-cfd") (.exists (io/file %))))
           first)))

(defn- shape-of
  "Map the aero case's afterbody taper to a kami-cfd body profile."
  [case]
  (if (>= (get-in case [:shape :taper] 0.5) 0.5) "teardrop" "block"))

(defn solve
  "Run kami-cfd for `case`; return {:Cd .. :sectional-cd .. :solver :lbm}."
  [case]
  (let [bin   (or (resolve-bin)
                  (throw (ex-info "kami-cfd binary not found; set KAMI_CFD_BIN or build it"
                                  {:tried "KAMI_CFD_BIN / sibling kami-cfd build"})))
        re    (get-in case [:solver :re] 100)
        steps (get-in case [:solver :steps] 3000)
        {:keys [exit out err]} (sh/sh bin (shape-of case) (str re) (str steps))
        _     (when-not (zero? exit)
                (throw (ex-info "kami-cfd failed" {:exit exit :err err})))
        m     (edn/read-string (str/trim out))
        sect  (:sectional-cd m)
        cd    (* calibration sect)]
    {:Cd cd :CdA (* cd (:frontal-area case))
     :sectional-cd sect :backend :kami-cfd
     :shape (keyword (shape-of case)) :solver :lbm}))

;; Register the high-fidelity backend on the shared contract.
(defmethod cae/solve :lbm [case] (solve case))
