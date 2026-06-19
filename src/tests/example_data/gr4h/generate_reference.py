"""
Generate GR4H (and GR4J control) reference fixtures for Kalix's Phase-3
validation tests.

Oracle: hydrogr (https://github.com/SimonDelmas/hydrogr), a Python-callable port
of INRAE's airGR whose CI asserts agreement with airGR reference results
(e.g. GR4H test: round(rmse,4) == round(air_gr_rmse,4) on catchment L0123003).

Driving data: airGR's canonical sample catchments (L0123003 hourly, L0123001 daily).
Parameters:   airGR's published values, taken from hydrogr's own test suite.
Initial stores: BOTH pinned to 0 (empty) to match Kalix's initialize(), which
                sidesteps airGR's default 0.3*X1 / 0.5*X3 convention.

Output column `flow` is per-step runoff depth in mm/step -> compared against
Kalix's Gr4j::run_step() return value (runoff_depth_mm).

Usage:
    curl -sL https://raw.githubusercontent.com/SimonDelmas/hydrogr/master/data/L0123003.csv -o /tmp/L0123003.csv
    curl -sL https://raw.githubusercontent.com/SimonDelmas/hydrogr/master/data/L0123001.csv -o /tmp/L0123001.csv
    python src/tests/example_data/gr4h/generate_reference.py
"""
import pandas as pd
from hydrogr import ModelGr4h, ModelGr4j

OUT_DIR = "src/tests/example_data/gr4h"


def generate(model_cls, src, date_format, params, out):
    df = pd.read_csv(src)
    df.columns = ["date", "precipitation", "temperature", "evapotranspiration", "flow", "flow_mm"]
    df["date"] = pd.to_datetime(df["date"], format=date_format)
    df.index = df["date"]

    model = model_cls(params)
    # Pin BOTH stores empty (fraction 0.0) to match Kalix's zero-init.
    model.set_states({"production_store": 0.0, "routing_store": 0.0,
                      "uh1": None, "uh2": None})
    out_df = model.run(df)  # run() wraps the raw DataFrame internally

    ref = pd.DataFrame({
        "timestamp": df.index.strftime("%Y-%m-%dT%H:%M:%S"),
        "precip_mm": df["precipitation"].values,
        "pet_mm": df["evapotranspiration"].values,
        "runoff_mm": out_df["flow"].values,
    })
    ref.to_csv(out, index=False, float_format="%.12g")
    print(f"wrote {len(ref):>6} rows -> {out}")
    print("   precip sum=%.3f max=%.3f | pet sum=%.3f | runoff sum=%.3f max=%.3f min=%.6f"
          % (ref.precip_mm.sum(), ref.precip_mm.max(), ref.pet_mm.sum(),
             ref.runoff_mm.sum(), ref.runoff_mm.max(), ref.runoff_mm.min()))


# GR4H (sub-daily) on the hourly catchment L0123003
generate(ModelGr4h, "/tmp/L0123003.csv", "%d/%m/%Y %H:%M",
         {"X1": 521.113, "X2": -2.918, "X3": 218.009, "X4": 4.124},
         f"{OUT_DIR}/gr4h_airgr_reference.csv")

# GR4J (daily) control on the daily catchment L0123001
generate(ModelGr4j, "/tmp/L0123001.csv", "%d/%m/%Y",
         {"X1": 257.238, "X2": 1.012, "X3": 88.235, "X4": 2.208},
         f"{OUT_DIR}/gr4j_airgr_reference.csv")
