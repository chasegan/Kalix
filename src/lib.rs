#[macro_use]
extern crate ini;

pub mod data_cache;
pub mod hydrology;
pub mod io;
pub mod model;
pub mod nodes;
mod numerical;
mod pybindings;
pub mod tests;
pub mod tid;
pub mod timeseries;
pub mod timeseries_input;

use pyo3::prelude::*;
use rand::Rng;
use std::cmp::Ordering;

//use std::io;

#[pyfunction]
fn guess_the_number() {
    println!("Guess the number!");

    let secret_number = rand::thread_rng().gen_range(1..101);

    loop {
        println!("Please input your guess.");

        let mut guess = String::new();

        std::io::stdin()
            .read_line(&mut guess)
            .expect("Failed to read line");

        let guess: u32 = match guess.trim().parse() {
            Ok(num) => num,
            Err(_) => continue,
        };

        println!("You guessed: {}", guess);

        match guess.cmp(&secret_number) {
            Ordering::Less => println!("Too small!"),
            Ordering::Greater => println!("Too big!"),
            Ordering::Equal => {
                println!("You win!");
                break;
            }
        }
    }
}

#[pyfunction]
pub fn load(s: &str) 
// -> PyResult<pybindings::Model> 
{
    // TODO
    // Ok(pybindings::Model {
    //     model: model::Model::new(),
    // })
}

/// A Python module implemented in Rust. The name of this function must match
/// the `lib.name` setting in the `Cargo.toml`, else Python will not be able to
/// import the module.
#[pymodule]
fn kalixpy(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(guess_the_number, m)?)?;
    m.add_function(wrap_pyfunction!(load, m)?)?;
    m.add_class::<pybindings::Model>()?;
    Ok(())
}
