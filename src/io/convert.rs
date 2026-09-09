//! One conversion path shared by the CLI (`kalix convert`) and the Python
//! API (`kalix.convert`), so the two can never drift (issue #11).
//!
//! CSV (plain or zip-compressed `.csv.zip`) and Pixie for now — the formats
//! the engine can both read and write.
//! (`.res.csv` is read on the IDE side only; the engine has no writer, so it
//! is refused by name rather than silently mistaken for plain CSV.) A future
//! format joins by extending [`TsFileFormat`] and the two match arms in
//! [`convert_ts_file`].

use std::fs;

use crate::io::csv_io;
use crate::io::pixie_io;
use crate::timeseries::Timeseries;

/// The timeseries file formats the converter speaks, named by extension.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum TsFileFormat {
    /// Date-indexed CSV (`.csv`).
    Csv,
    /// Zip-compressed date-indexed CSV (`.csv.zip`) — the same grammar as
    /// [`TsFileFormat::Csv`] inside a one-entry zip archive (pandas'
    /// convention: exactly one file per archive).
    CsvZip,
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
    // Longest suffix first — the double-extension rule (.res.csv.zip before
    // .csv.zip before .csv), as everywhere else in the codebase.
    let lower = path.to_lowercase();
    if lower.ends_with(".res.csv") || lower.ends_with(".res.csv.zip") {
        Err(format!(
            "'{path}': .res.csv is not supported yet — the engine converts csv, csv.zip and pixie only"
        ))
    } else if lower.ends_with(".csv.zip") {
        Ok(TsFileFormat::CsvZip)
    } else if lower.ends_with(".csv") {
        Ok(TsFileFormat::Csv)
    } else if lower.ends_with(".pxt") || lower.ends_with(".pxb") {
        Ok(TsFileFormat::Pixie)
    } else {
        Err(format!(
            "'{path}': unrecognised timeseries format (expected .csv, .csv.zip, .pxt or .pxb)"
        ))
    }
}

/// Suffix for the sibling files a conversion writes before renaming into
/// place. Keeps a failed write from ever destroying an existing output — the
/// original (which may be the input) is only touched by the final rename.
const TMP_SUFFIX: &str = ".kalix_tmp";

/// Converts one timeseries file to another format (or re-encodes within a
/// format). Pixie output is written lossless (64-bit values). The input is
/// read completely first, and the output is written to a temporary sibling
/// and renamed into place, so converting a file onto itself is safe and a
/// failed write never destroys an existing file.
pub fn convert_ts_file(input: &str, output: &str) -> Result<ConvertSummary, String> {
    let input_format = detect_ts_format(input)?;
    let output_format = detect_ts_format(output)?;

    let series: Vec<Timeseries> = match input_format {
        TsFileFormat::Csv | TsFileFormat::CsvZip => {
            // read_ts keys zip off the real input name.
            csv_io::read_ts(input).map_err(|e| format!("reading '{input}': {e}"))?
        }
        TsFileFormat::Pixie => {
            let base = pixie_io::strip_pixie_extension(input).unwrap_or(input);
            pixie_io::read_all_series(base)
                .map_err(|e| format!("reading '{base}.pxt/.pxb': {e}"))?
        }
    };
    if series.is_empty() {
        return Err(format!("'{input}' contains no series"));
    }
    let n_series = series.len();
    let n_points = series.iter().map(|s| s.values.len()).sum();
    let refs: Vec<&Timeseries> = series.iter().collect();

    let outputs = match output_format {
        TsFileFormat::Csv | TsFileFormat::CsvZip => {
            // CSV shares one time column across every series; Pixie stores a
            // clock per series. Refuse a misaligned set rather than silently
            // stamping every series with the first one's clock.
            let (t0, dt) = (series[0].start_timestamp, series[0].step_size);
            if let Some(s) = series
                .iter()
                .find(|s| s.start_timestamp != t0 || s.step_size != dt)
            {
                return Err(format!(
                    "cannot write '{output}': CSV shares one time column, but series '{}' \
                     has a different start or timestep to '{}'",
                    s.name, series[0].name
                ));
            }
            // The temp name's extension lies about both the format and the
            // archive's entry name, so both derive from the real output name.
            let tmp = format!("{output}{TMP_SUFFIX}");
            let inner = if output_format == TsFileFormat::CsvZip {
                Some(csv_io::zip_inner_name(output))
            } else {
                None
            };
            csv_io::write_ts_opts(&tmp, refs, inner.as_deref()).map_err(|e| {
                let _ = fs::remove_file(&tmp);
                format!("writing '{output}': {}", String::from(e))
            })?;
            fs::rename(&tmp, output).map_err(|e| {
                let _ = fs::remove_file(&tmp);
                format!("writing '{output}': {e}")
            })?;
            vec![output.to_string()]
        }
        TsFileFormat::Pixie => {
            let base = pixie_io::strip_pixie_extension(output).unwrap_or(output);
            let tmp_base = format!("{base}{TMP_SUFFIX}");
            let cleanup = || {
                let _ = fs::remove_file(format!("{tmp_base}.pxt"));
                let _ = fs::remove_file(format!("{tmp_base}.pxb"));
            };
            pixie_io::write_series_with_precision(&tmp_base, &refs, true).map_err(|e| {
                cleanup();
                format!("writing '{base}.pxt/.pxb': {e}")
            })?;
            for ext in [".pxb", ".pxt"] {
                fs::rename(format!("{tmp_base}{ext}"), format!("{base}{ext}")).map_err(|e| {
                    cleanup();
                    format!("writing '{base}{ext}': {e}")
                })?;
            }
            vec![format!("{base}.pxt"), format!("{base}.pxb")]
        }
    };

    Ok(ConvertSummary { n_series, n_points, outputs })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// A per-process scratch path: two `cargo test` invocations racing on one
    /// machine must not share fixture files. Cleaned up by `Scratch::drop`.
    struct Scratch(String);

    impl Scratch {
        fn new(name: &str) -> Scratch {
            Scratch(
                std::env::temp_dir()
                    .join(format!("kalix_convert_{}_{}", std::process::id(), name))
                    .to_string_lossy()
                    .to_string(),
            )
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.0);
        }
    }

    #[test]
    fn csv_to_pixie_and_back_preserves_names_and_values() {
        let src = Scratch::new("src.csv");
        fs::write(
            &src.0,
            "Date,flow,level\n2020-01-01,1.5,10.0\n2020-01-02,2.75,20.0\n",
        )
        .unwrap();

        let pxt = Scratch::new("mid.pxt");
        let pxb = Scratch::new("mid.pxb");
        let summary = convert_ts_file(&src.0, &pxt.0).expect("csv -> pixie");
        assert_eq!(summary.n_series, 2);
        assert_eq!(summary.n_points, 4);
        assert_eq!(summary.outputs, vec![pxt.0.clone(), pxb.0.clone()],
            "a pixie output is both halves");

        let back = Scratch::new("back.csv");
        convert_ts_file(&pxt.0, &back.0).expect("pixie -> csv");
        let reread = csv_io::read_ts(&back.0).expect("re-read the round trip");
        assert_eq!(reread.len(), 2);
        assert_eq!(reread[0].name, "flow", "names survive the round trip");
        assert_eq!(reread[1].name, "level");
        assert_eq!(reread[0].values, vec![1.5, 2.75], "values survive exactly");
        assert_eq!(reread[1].values, vec![10.0, 20.0]);
    }

    #[test]
    fn pixie_values_survive_at_full_double_precision() {
        // 0.1 is not f32-exact: this fails if the pixie write path ever drops
        // back to the 32-bit codec.
        let src = Scratch::new("precise.csv");
        fs::write(&src.0, "Date,x\n2020-01-01,0.1\n2020-01-02,0.2\n").unwrap();

        let pxt = Scratch::new("precise.pxt");
        let _pxb = Scratch::new("precise.pxb");
        convert_ts_file(&src.0, &pxt.0).expect("csv -> pixie");
        let reread = pixie_io::read_all_series(
            pixie_io::strip_pixie_extension(&pxt.0).unwrap(),
        )
        .expect("re-read the pixie pair");
        assert_eq!(reread[0].values, vec![0.1, 0.2], "lossless, not f32");
    }

    #[test]
    fn misaligned_pixie_series_are_refused_for_csv() {
        // CSV has one time column; two series with different starts can't
        // share it honestly.
        let mut a = Timeseries::new_daily();
        a.name = "a".to_string();
        a.start_timestamp = crate::tid::utils::wrap_to_u64(1577836800); // 2020-01-01
        a.values = vec![1.0, 2.0];
        let mut b = a.clone();
        b.name = "b".to_string();
        b.start_timestamp = a.start_timestamp + 86400;

        let pxt = Scratch::new("misaligned.pxt");
        let pxb = Scratch::new("misaligned.pxb");
        let base = pixie_io::strip_pixie_extension(&pxt.0).unwrap();
        pixie_io::write_series_with_precision(base, &[&a, &b], true).unwrap();
        assert!(fs::metadata(&pxb.0).is_ok(), "fixture wrote both halves");

        let out = Scratch::new("misaligned_out.csv");
        let err = convert_ts_file(&pxt.0, &out.0).unwrap_err();
        assert!(err.contains("time column"), "{err}");
        assert!(fs::metadata(&out.0).is_err(), "nothing was written");
    }

    #[test]
    fn converting_a_file_onto_itself_is_safe() {
        let src = Scratch::new("inplace.csv");
        fs::write(&src.0, "Date,q\n2020-01-01,3.5\n2020-01-02,4.5\n").unwrap();

        convert_ts_file(&src.0, &src.0).expect("csv -> same csv");
        let reread = csv_io::read_ts(&src.0).expect("re-read after in-place re-encode");
        assert_eq!(reread[0].values, vec![3.5, 4.5]);
    }

    #[test]
    fn csv_zip_round_trips_and_decompresses_to_the_plain_csv_bytes() {
        let content = "Date,flow,level\n2020-01-01,1.5,10.0\n2020-01-02,2.75,20.0\n";
        let src = Scratch::new("zip_src.csv");
        fs::write(&src.0, content).unwrap();

        // The cross-platform pin (issue #409): a .csv.zip's single entry must
        // decompress to exactly the bytes the plain CSV writer would produce,
        // under the entry name pandas expects (archive name minus .zip).
        let plain = Scratch::new("zip_plain.csv");
        let zipped = Scratch::new("zip_out.csv.zip");
        convert_ts_file(&src.0, &plain.0).expect("csv -> csv");
        convert_ts_file(&src.0, &zipped.0).expect("csv -> csv.zip");

        let mut archive = zip::ZipArchive::new(fs::File::open(&zipped.0).unwrap())
            .expect("output is a valid zip archive");
        assert_eq!(archive.len(), 1, "exactly one entry");
        let mut entry = archive.by_index(0).unwrap();
        assert!(entry.name().ends_with(".csv") && !entry.name().contains("kalix_tmp"),
            "entry '{}' must be named from the real archive name minus .zip, \
             never from the temp staging name", entry.name());
        let mut decompressed = Vec::new();
        std::io::Read::read_to_end(&mut entry, &mut decompressed).unwrap();
        assert_eq!(decompressed, fs::read(&plain.0).unwrap(),
            "decompressed bytes must equal the plain CSV writer's output");
        drop(entry);
        drop(archive);

        // And the data survives the full round trip.
        let back = Scratch::new("zip_back.csv");
        convert_ts_file(&zipped.0, &back.0).expect("csv.zip -> csv");
        let reread = csv_io::read_ts(&back.0).unwrap();
        assert_eq!(reread[0].name, "flow");
        assert_eq!(reread[0].values, vec![1.5, 2.75]);
        assert_eq!(reread[1].values, vec![10.0, 20.0]);
    }

    #[test]
    fn csv_zip_output_is_byte_reproducible() {
        // The entry timestamp is pinned to the DOS epoch, so identical
        // content zips to identical bytes — rewriting a result must not churn
        // the file. (Within one build only: a zip/flate2 upgrade may
        // legitimately change the encoding; the cross-version contract is
        // decompressed content, not bytes.)
        // Same TARGET both times: the entry name derives from the archive
        // name, so archives at different paths legitimately differ by it.
        let src = Scratch::new("repro.csv");
        fs::write(&src.0, "Date,q\n2020-01-01,1.0\n2020-01-02,2.0\n").unwrap();
        let out = Scratch::new("repro.csv.zip");
        convert_ts_file(&src.0, &out.0).unwrap();
        let first = fs::read(&out.0).unwrap();
        convert_ts_file(&src.0, &out.0).unwrap();
        assert_eq!(first, fs::read(&out.0).unwrap());
    }

    #[test]
    fn multi_entry_zip_is_refused_like_pandas() {
        // Pandas parity: a .csv.zip holds exactly one file; reading "the
        // first of several" would silently guess which one the data is.
        use std::io::Write;
        let multi = Scratch::new("multi.csv.zip");
        let mut writer = zip::ZipWriter::new(fs::File::create(&multi.0).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        for (name, body) in [("a.csv", "Date,q\n2020-01-01,1.0\n"), ("b.csv", "Date,q\n2020-01-01,2.0\n")] {
            writer.start_file(name, options).unwrap();
            writer.write_all(body.as_bytes()).unwrap();
        }
        writer.finish().unwrap();

        let err = csv_io::read_ts(&multi.0).err().expect("a two-entry archive must refuse");
        assert!(format!("{err}").contains("exactly one file"), "{err}");
    }

    #[test]
    fn res_csv_zip_is_refused_like_res_csv() {
        let err = convert_ts_file("results.res.csv.zip", "out.csv").unwrap_err();
        assert!(err.contains(".res.csv"), "{err}");
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
