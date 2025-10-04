
pub fn make_result_name(node_name: &str, parameter: &str) -> String {
    format!("node.{node_name}.{parameter}")
}

pub fn split_interleaved(interleaved: &[f64]) -> (Vec<f64>, Vec<f64>) {
    let x_values: Vec<f64> = interleaved.iter().step_by(2).copied().collect();
    let y_values: Vec<f64> = interleaved.iter().skip(1).step_by(2).copied().collect();
    (x_values, y_values)
}