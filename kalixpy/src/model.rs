use numpy::PyArray;
use pyo3::prelude::*;

use kalix_lib::model as kalix_model;

// https://pyo3.rs/v0.23.3/class.html#defining-a-new-class
// This should be a wrapper for a kalix Model, with methods calling said Model's methods
#[pyclass]
pub struct Model {
    model: kalix_model::Model,
}

#[pymethods]
impl Model {
    // #[staticmethod]
    // pub fn new() -> Model {
    //     Model {
    //         model: { model::Model::new() },
    //     }
    // }

    // reload from str
    fn reload(&mut self, s: &str) -> PyResult<()> {
        // TODO implement
        Ok(())
    }

    // run -> ()
    fn run(&mut self) -> PyResult<()> {
        self.model.run();
        Ok(())
    }

    // get result names -> std::Vec<str> ?
    fn get_result_names(&self, s: &str)
    // TODO -> PyResult<&'py Vec<String>>
    {
        // TODO implement
    }

    // get result -> numpy array
    fn get_result(&self, s: &str)
    // TODO -> PyResult<&'py PyArray<f64, ndarray::Dim<[usize; 1]>>>
    {
        // TODO implement
    }
}
