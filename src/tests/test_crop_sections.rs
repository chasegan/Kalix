// Tests for [crop.*] sections: a crop declared once and referenced by fields.
// Numbers and one table, nothing evaluated.

use crate::io::ini_model_io::IniModelIO;
use crate::hydrology::crop::{Crop, CropRegistry, KcCurve};
use crate::misc::configuration::SaveMethod;

fn model_with(crops: &str) -> String {
    format!("[kalix]\nstart = 2020-01-01\nend = 2020-01-03\n\n{crops}\n\n[node.g]\ntype = gauge\nloc = 0, 0\n")
}

fn load_err(ini: &str) -> String {
    IniModelIO::read_model_string(ini).err().expect("should not load").to_string()
}

#[test]
fn test_a_crop_with_a_kc_curve_and_a_season() {
    let ini = model_with("[crop.cotton]\nroot_depth = 900\np = 0.65\nkc = Day, Kc,\n     0,   0.35,\n     30,  0.35,\n     70,  1.2,\n     130, 1.2,\n     180, 0.6,\nseason_len = 180");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let crop = model.crops.get(model.crops.get_idx("cotton").expect("declared"));
    assert_eq!(crop.root_depth, 900.0);
    assert_eq!(crop.p, 0.65);
    assert_eq!(crop.season_len, Some(180));
    // The curve: flat, rising, flat, falling; held beyond the ends
    assert_eq!(crop.kc.at(0.0), 0.35);
    assert!((crop.kc.at(50.0) - 0.775).abs() < 1e-12, "halfway up the rise");
    assert_eq!(crop.kc.at(100.0), 1.2);
    assert!((crop.kc.at(155.0) - 0.9).abs() < 1e-12, "halfway down the fall");
    assert_eq!(crop.kc.at(400.0), 0.6, "the last value carries on");
    assert_eq!(crop.kc.at(-5.0), 0.35, "and the first one back");
}

#[test]
fn test_a_perennial_with_a_constant_kc() {
    let ini = model_with("[crop.fallow]\nroot_depth = 600\nkc = 0.6");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let crop = model.crops.get(model.crops.get_idx("fallow").unwrap());
    assert!(matches!(crop.kc, KcCurve::Constant(kc) if kc == 0.6));
    assert_eq!(crop.p, 0.5, "the default");
    assert_eq!(crop.season_len, None, "never harvested");
    assert_eq!(crop.kc.at(1000.0), 0.6);
}

#[test]
fn test_crop_validation_names_the_crop_and_the_line() {
    assert!(load_err(&model_with("[crop.Cotton]\nroot_depth = 900\nkc = 1")).contains("Invalid crop name 'Cotton'"));
    assert!(load_err(&model_with("[crop.cotton]\nkc = 1")).contains("Crop 'cotton' has no 'root_depth'"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900")).contains("Crop 'cotton' has no 'kc'"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 0\nkc = 1")).contains("root_depth must be a positive number"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\np = 1\nkc = 1")).contains("p must be at least 0 and less than 1"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = 0, 0.3, 30, 0.5, 20, 1.0")).contains("kc table days must increase"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = 0, 0.3, 30")).contains("as a number or a two-column table"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = Day, 0.3, 30, 0.5")).contains("starts with one label, 'Day'"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = 1\nseason_len = 0")).contains("season_len must be at least 1 day"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = 1\nseason_len = 90.5")).contains("season_len must be a whole number of days"));
    assert!(load_err(&model_with("[crop.cotton]\nroot_depth = 900\nkc = 1\nyield = 2")).contains("Unexpected property 'yield' in section '[crop.cotton]'"));
}

#[test]
fn test_the_registry_refuses_a_second_crop_of_the_same_name() {
    let mut crops = CropRegistry::default();
    let cotton = Crop { name: "cotton".into(), root_depth: 900.0, p: 0.5, kc: KcCurve::Constant(1.0), season_len: None };
    assert_eq!(crops.insert(cotton.clone()), Ok(0));
    assert!(crops.insert(cotton).unwrap_err().contains("declared twice"));
    assert_eq!(crops.get_idx("COTTON"), Some(0), "names match regardless of case");
    assert_eq!(crops.get_idx("wheat"), None);
}

#[test]
fn test_crops_round_trip() {
    let ini = model_with("[crop.wheat]\nroot_depth = 600\nkc = 0.9\n\n[crop.cotton]\nroot_depth = 900\np = 0.65\nkc = 0, 0.35, 70, 1.2, 180, 0.6,\nseason_len = 180");
    let mut model = IniModelIO::read_model_string(&ini).expect("model should load");
    model.configuration.save_method = SaveMethod::Canonical;
    let rendered = IniModelIO::model_to_string(&model);
    for line in ["[crop.cotton]", "root_depth = 900", "p = 0.65", "season_len = 180", "[crop.wheat]", "kc = 0.9", "kc = 0, 0.35, \n    70, 1.2, \n    180, 0.6, "] {
        assert!(rendered.contains(line), "'{line}' survives save:\n{rendered}");
    }
    assert!(!rendered.contains("season_len = 0"), "a perennial writes no season_len");
    let mut reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    reloaded.configuration.save_method = SaveMethod::Canonical;
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
    assert!((reloaded.crops.get(reloaded.crops.get_idx("cotton").unwrap()).kc.at(35.0) - 0.775).abs() < 1e-12);
}

