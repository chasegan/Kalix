//! One conversion path shared by the CLI (`kalix convert`) and the Python
//! API (`kalix.convert`), so the two can never drift (issue #11).
//!
//! CSV and Pixie for now — the formats the engine can both read and write.
//! (`.res.csv` is read on the IDE side only; the engine has no writer, so it
//! is refused by name rather than silently mistaken for plain CSV.) A future
//! format joins by extending [`TsFileFormat`] and the two match arms in
//! [`convert_ts_file`].

use crate::io::csv_io;
use crate::io::pixie_io;
use crate::timeseries::Timeseries;

/// The timeseries file formats the converter speaks, named by extension.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum TsFileFormat {
    /// Date-indexed CSV (`.csv`).
    Csv,
    /// The Pixie pair (`.pxt` metadata + `.pxb` Gorilla-compressed values);
    /// either half names the dataset, and both halves are always written.
    Pixie,
}

/// What a conversion did: the CLI's summary line and the Python return value.
#[derive(Debug)]
pub struct ConvertSummary {
    pub n_series: usize,
    pub n_points: usize,
    /// Every file actually written (a Pixie output is two files).
    pub outputs: Vec<String>,
}

/// Names the format of `path` by extension, refusing what the engine cannot
/// honestly convert rather than guessing.
pub fn detect_ts_format(path: &str) -> Result<TsFileFormat, String> {
    let lower = path.to_lowercase();
    if lower.ends_with(".res.csv") {
        Err(format!(
            "'{path}': .res.csv is not supported yet — the engine converts csv and pixie only"
        ))
    } else if lower.ends_with(".csv") {
        Ok(TsFileFormat::Csv)
    } else if lower.ends_with(".pxt") || lower.ends_with(".pxb") {
        Ok(TsFileFormat::Pixie)
    } else {
        Err(format!(
            "'{path}': unrecognised timeseries format (expected .csv, .pxt or .pxb)"
        ))
    }
}

/// Converts one timeseries file to another format (or re-encodes within a
/// format). The input is read completely before the output is written, so
/// converting a file onto itself is safe.
pub fn convert_ts_file(input: &str, output: &str) -> Result<ConvertSummary, String> {
    let input_format = detect_ts_format(input)?;
    let output_format = detect_ts_format(output)?;

    let series: Vec<Timeseries> = match input_format {
        TsFileFormat::Csv => {
            csv_io::read_ts(input).map_err(|e| format!("reading '{input}': {e:?}"))?
        }
        TsFileFormat::Pixie => {
            let base = pixie_io::strip_pixie_extension(input).unwrap_or(input);
            pixie_io::read_all_series(base)
                .map_err(|e| format!("reading '{base}.pxt/.pxb': {e:?}"))?
        }
    };
    if series.is_empty() {
        return Err(format!("'{input}' contains no series"));
    }
    let n_series = series.len();
    let n_points = series.iter().map(|s| s.values.len()).sum();
    let refs: Vec<&Timeseries> = series.iter().collect();

    let outputs = match output_format {
        TsFileFormat::Csv => {
            csv_io::write_ts(output, refs).map_err(|e| format!("writing '{output}': {e:?}"))?;
            vec![output.to_string()]
        }
        TsFileFormat::Pixie => {
            let base = pixie_io::strip_pixie_extension(output).unwrap_or(output);
            pixie_io::write_series(base, &refs)
                .map_err(|e| format!("writing '{base}.pxt/.pxb': {e:?}"))?;
            vec![format!("{base}.pxt"), format!("{base}.pxb")]
        }
    };

    Ok(ConvertSummary { n_series, n_points, outputs })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp(name: &str) -> String {
        std::env::temp_dir().join(name).to_string_lossy().to_string()
    }

    #[test]
    fn csv_to_pixie_and_back_preserves_names_and_values() {
        let src = temp("kalix_convert_src.csv");
        fs::write(
            &src,
            "Date,flow,level\n2020-01-01,1.5,10.0\n2020-01-02,2.75,20.0\n",
        )
        .unwrap();

        let pxt = temp("kalix_convert_mid.pxt");
        let summary = convert_ts_file(&src, &pxt).expect("csv -> pixie");
        assert_eq!(summary.n_series, 2);
        assert_eq!(summary.n_points, 4);
        assert_eq!(summary.outputs.len(), 2, "a pixie output is both halves");

        let back = temp("kalix_convert_back.csv");
        convert_ts_file(&pxt, &back).expect("pixie -> csv");
        let reread = csv_io::read_ts(&back).expect("re-read the round trip");
        assert_eq!(reread.len(), 2);
        assert_eq!(reread[0].values, vec![1.5, 2.75], "values survive exactly");
        assert_eq!(reread[1].values, vec![10.0, 20.0]);
    }

    #[test]
    fn unknown_extensions_are_refused_by_name() {
        let err = convert_ts_file("data.parquet", "out.csv").unwrap_err();
        assert!(err.contains("unrecognised"), "{err}");
        let err = convert_ts_file("in.csv", "out.xlsx").unwrap_err();
        assert!(err.contains("unrecognised"), "{err}");
    }

    #[test]
    fn res_csv_is_refused_honestly_not_mistaken_for_plain_csv() {
        let err = convert_ts_file("results.res.csv", "out.pxt").unwrap_err();
        assert!(err.contains(".res.csv"), "{err}");
    }
}
