use crate::hydrology::routing::unit_hydrograph::uh_dyn;
use crate::hydrology::routing::unit_hydrograph::uh_prealloc_32;


#[test]
fn test_uh_dyn1() {
    let mut uhd = uh_dyn::UHDyn::new(2);
    uhd.set_kernel(0, 0.9);
    uhd.set_kernel(1, 0.1);
    let v1 = uhd.run_step(1.0);
    let v2 = uhd.run_step(1.0);
    let v3 = uhd.run_step(0.0);
    let v4 = uhd.run_step(0.0);
    assert_eq!(v1, 0.9);
    assert_eq!(v2, 1.0);
    assert_eq!(v3, 0.1);
    assert_eq!(v4, 0.0);
}

#[test]
fn test_uh_dyn2() {
    let mut uhd = uh_dyn::UHDyn::new(3);
    uhd.set_kernel(0, 0.0);
    uhd.set_kernel(1, 0.9);
    uhd.set_kernel(2, 0.1);
    uhd.reset();
    let v1 = uhd.run_step(1.0);
    let v2 = uhd.run_step(1.0);
    let v3 = uhd.run_step(0.0);
    let v4 = uhd.run_step(0.0);
    let v5 = uhd.run_step(0.0);
    assert_eq!(v1, 0.0);
    assert_eq!(v2, 0.9);
    assert_eq!(v3, 1.0);
    assert_eq!(v4, 0.1);
    assert_eq!(v5, 0.0);
    //println!("{v1} {v2} {v3} {v4} {v5}");
}


#[test]
fn test_uh_prealloc32_1() {
    let mut uhd = uh_prealloc_32::UHPrealloc32::new(2);
    uhd.set_kernel(0, 0.9);
    uhd.set_kernel(1, 0.1);
    let v1 = uhd.run_step(1.0);
    let v2 = uhd.run_step(1.0);
    let v3 = uhd.run_step(0.0);
    let v4 = uhd.run_step(0.0);
    assert_eq!(v1, 0.9);
    assert_eq!(v2, 1.0);
    assert_eq!(v3, 0.1);
    assert_eq!(v4, 0.0);
    //println!("{v1} {v2} {v3} {v4}");
}


#[test]
fn test_uh_prealloc32_2() {
    let mut uhd = uh_prealloc_32::UHPrealloc32::new(3);
    uhd.set_kernel(0, 0.0);
    uhd.set_kernel(1, 0.9);
    uhd.set_kernel(2, 0.1);
    uhd.reset();
    let v1 = uhd.run_step(1.0);
    let v2 = uhd.run_step(1.0);
    let v3 = uhd.run_step(0.0);
    let v4 = uhd.run_step(0.0);
    let v5 = uhd.run_step(0.0);
    assert_eq!(v1, 0.0);
    assert_eq!(v2, 0.9);
    assert_eq!(v3, 1.0);
    assert_eq!(v4, 0.1);
    assert_eq!(v5, 0.0);
    //println!("{v1} {v2} {v3} {v4} {v5}");
}