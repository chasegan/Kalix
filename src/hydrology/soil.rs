//! The soil under a field, kept as layers so that water below one crop's roots
//! is still there for the next. The reasoning and the references are in the
//! field page of the user docs (website/docs/docs/field.md, "How the node
//! works" and "References"): FAO-56's root zone does not drain below field
//! capacity, and USDA-ARS's pyfao56 keeps the same depletion-below-the-roots.
//!
//! The layers' boundaries are the field's distinct root depths, fallow
//! included, so every crop's root zone is a whole number of layers. Each
//! partition of the field (the fallow, and each crop in the ground) holds one
//! bucket for its root zone, which the plants and the irrigator work, and one
//! depletion per layer below it, which only overflow and transfers touch.
//!
//! Everything is in mm of water over the partition's area; a depletion is how
//! far below full: 0 full, the capacity empty.

/// One more than the crop slots a field can hold: each slot's crop, and the fallow
pub const MAX_LAYERS: usize = crate::nodes::field_node::MAX_CROPS + 1;

/// The layering a field's soil is kept in: the same for every partition
#[derive(Clone, Default)]
pub struct Profile {
    pub n_layers: usize,
    /// mm each layer holds between full and empty
    pub capacity: [f64; MAX_LAYERS],
    /// mm, the bottom of each layer, ascending
    pub bottom: [f64; MAX_LAYERS],
}

impl Profile {
    /// Layers bounded by the given root depths (mm, any order, repeats allowed),
    /// holding `available_water` mm of water per metre of soil.
    pub fn new(available_water: f64, root_depths: &[f64]) -> Profile {
        let mut depths: Vec<f64> = root_depths.to_vec();
        depths.sort_by(|a, b| a.partial_cmp(b).unwrap());
        depths.dedup();
        assert!(depths.len() <= MAX_LAYERS, "at most {MAX_LAYERS} distinct root depths");
        let mut profile = Profile { n_layers: depths.len(), ..Default::default() };
        let mut top = 0.0;
        for (i, depth) in depths.iter().enumerate() {
            profile.bottom[i] = *depth;
            profile.capacity[i] = available_water * (depth - top) / 1000.0;
            top = *depth;
        }
        profile
    }

    /// How many layers a root zone of this depth covers. The depth is one of
    /// the profile's boundaries by construction.
    pub fn root_layers(&self, root_depth: f64) -> usize {
        self.bottom[..self.n_layers].iter().position(|b| *b == root_depth)
            .map(|i| i + 1)
            .expect("a root depth is one of the profile's layer boundaries")
    }

    pub fn total_capacity(&self) -> f64 {
        self.capacity[..self.n_layers].iter().sum()
    }
}

/// A part of the field under one cover: its area, its root bucket, and the
/// layers below the roots
#[derive(Clone, Copy, Default)]
pub struct Partition {
    /// km2
    pub area: f64,
    /// The root bucket covers layers 0..root_layers
    pub root_layers: usize,
    /// mm, the sum of the root layers' capacities
    pub root_capacity: f64,
    /// mm below full, over the root zone
    pub root_depletion: f64,
    /// mm below full, per layer; meaningful from root_layers down
    pub layer_depletion: [f64; MAX_LAYERS],
}

impl Partition {
    /// A partition rooted to `root_depth`, every layer `initial_depletion` mm
    /// below full over the whole profile, spread by capacity.
    pub fn new(profile: &Profile, root_depth: f64, area: f64, initial_depletion: f64) -> Partition {
        let root_layers = profile.root_layers(root_depth);
        let fraction = initial_depletion / profile.total_capacity();
        let mut layer_depletion = [0.0; MAX_LAYERS];
        for i in 0..profile.n_layers {
            layer_depletion[i] = fraction * profile.capacity[i];
        }
        let root_capacity: f64 = profile.capacity[..root_layers].iter().sum();
        Partition {
            area,
            root_layers,
            root_capacity,
            root_depletion: layer_depletion[..root_layers].iter().sum(),
            layer_depletion,
        }
    }

    /// The root bucket has more water than it holds, by `overflow` mm: the
    /// layers below take it in turn, and what passes the last is returned.
    pub fn drain_overflow(&mut self, profile: &Profile, mut overflow: f64) -> f64 {
        for i in self.root_layers..profile.n_layers {
            let taken = overflow.min(self.layer_depletion[i]);
            self.layer_depletion[i] -= taken;
            overflow -= taken;
            if overflow <= 0.0 { return 0.0; }
        }
        overflow
    }

    /// mm of water held over the whole profile
    pub fn water(&self, profile: &Profile) -> f64 {
        let below: f64 = self.layer_depletion[self.root_layers..profile.n_layers].iter().sum();
        profile.total_capacity() - self.root_depletion - below
    }

    /// The root bucket spread uniformly over its layers: every layer as wet as
    /// the bucket, the layers below as they are
    fn spread(&self, profile: &Profile) -> [f64; MAX_LAYERS] {
        let mut layers = self.layer_depletion;
        let fraction = if self.root_capacity > 0.0 { self.root_depletion / self.root_capacity } else { 0.0 };
        for i in 0..self.root_layers {
            layers[i] = fraction * profile.capacity[i];
        }
        layers
    }

    /// The reverse: the root layers pooled into the bucket
    fn pool(&mut self, layers: [f64; MAX_LAYERS]) {
        self.layer_depletion = layers;
        self.root_depletion = layers[..self.root_layers].iter().sum();
    }
}

/// Moves `area` km2 from one partition to another, with its water: the two are
/// spread over the layers, mixed layer by layer in proportion to area, and the
/// receiver's root layers pooled again. The giver's water per mm does not
/// change, only its area. Water is conserved by construction.
pub fn transfer(profile: &Profile, from: &mut Partition, to: &mut Partition, area: f64) {
    if area <= 0.0 { return; }
    let area = area.min(from.area);
    let new_area = to.area + area;
    if new_area <= 0.0 { return; }
    let from_layers = from.spread(profile);
    let mut to_layers = to.spread(profile);
    for i in 0..profile.n_layers {
        to_layers[i] = (to.area * to_layers[i] + area * from_layers[i]) / new_area;
    }
    to.pool(to_layers);
    to.area = new_area;
    from.area -= area;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f64, b: f64) -> bool { (a - b).abs() < 1e-9 }

    /// 150 mm/m; layers to 600 and 900 mm: capacities 90 and 45 mm
    fn profile() -> Profile { Profile::new(150.0, &[900.0, 600.0, 600.0]) }

    #[test]
    fn layers_are_the_distinct_root_depths() {
        let p = profile();
        assert_eq!(p.n_layers, 2);
        assert_eq!(&p.bottom[..2], &[600.0, 900.0]);
        assert!(close(p.capacity[0], 90.0) && close(p.capacity[1], 45.0));
        assert_eq!(p.root_layers(600.0), 1);
        assert_eq!(p.root_layers(900.0), 2);
        assert!(close(p.total_capacity(), 135.0));
    }

    #[test]
    fn initial_depletion_is_spread_by_capacity() {
        let p = profile();
        let shallow = Partition::new(&p, 600.0, 1.0, 27.0);
        assert!(close(shallow.root_depletion, 18.0), "two thirds of 27 in the top 90 of 135");
        assert!(close(shallow.layer_depletion[1], 9.0));
        assert!(close(shallow.water(&p), 108.0));
        let deep = Partition::new(&p, 900.0, 1.0, 27.0);
        assert!(close(deep.root_depletion, 27.0));
        assert!(close(deep.water(&p), 108.0));
    }

    #[test]
    fn overflow_fills_the_layers_below_before_leaving() {
        let p = profile();
        let mut shallow = Partition::new(&p, 600.0, 1.0, 0.0);
        shallow.layer_depletion[1] = 30.0;
        assert!(close(shallow.drain_overflow(&p, 10.0), 0.0));
        assert!(close(shallow.layer_depletion[1], 20.0));
        assert!(close(shallow.drain_overflow(&p, 25.0), 5.0), "5 passes the last layer");
        assert!(close(shallow.layer_depletion[1], 0.0));
        let mut deep = Partition::new(&p, 900.0, 1.0, 0.0);
        assert!(close(deep.drain_overflow(&p, 7.0), 7.0), "no layers below the deepest roots");
    }

    #[test]
    fn a_transfer_conserves_water_and_mixes_by_area() {
        let p = profile();
        // Fallow 3 km2, shallow roots, top dry-ish and subsoil full; a 900 mm crop
        // of 1 km2 that is wetter on top and dry below
        let mut fallow = Partition::new(&p, 600.0, 3.0, 0.0);
        fallow.root_depletion = 60.0;
        let mut crop = Partition::new(&p, 900.0, 1.0, 0.0);
        crop.root_depletion = 40.0;
        let before = fallow.area * fallow.water(&p) + crop.area * crop.water(&p);
        transfer(&p, &mut fallow, &mut crop, 1.0);
        assert!(close(fallow.area, 2.0) && close(crop.area, 2.0));
        assert!(close(fallow.root_depletion, 60.0), "the giver's wetness is unchanged");
        // The crop spread: 40 over 135 -> top 26.667, bottom 13.333; the fallow's
        // top 60, bottom 0; mixed half and half: top 43.333, bottom 6.667
        assert!(close(crop.root_depletion, 50.0));
        let after = fallow.area * fallow.water(&p) + crop.area * crop.water(&p);
        assert!(close(before, after), "water is conserved: {before} vs {after}");
    }

    #[test]
    fn harvest_returns_the_water_to_the_fallow_layer_by_layer() {
        let p = profile();
        let mut fallow = Partition::new(&p, 600.0, 0.0, 0.0);
        let mut crop = Partition::new(&p, 900.0, 2.0, 0.0);
        crop.root_depletion = 90.0; // spread: top 60, bottom 30
        let before = crop.area * crop.water(&p);
        transfer(&p, &mut crop, &mut fallow, 2.0);
        assert!(close(crop.area, 0.0) && close(fallow.area, 2.0));
        assert!(close(fallow.root_depletion, 60.0), "the top layer pools into the fallow's bucket");
        assert!(close(fallow.layer_depletion[1], 30.0), "the bottom keeps its value");
        assert!(close(fallow.area * fallow.water(&p), before));
    }

    #[test]
    fn a_transfer_into_nothing_or_of_nothing_does_nothing() {
        let p = profile();
        let mut a = Partition::new(&p, 600.0, 1.0, 10.0);
        let mut b = Partition::new(&p, 900.0, 0.0, 0.0);
        transfer(&p, &mut a, &mut b, 0.0);
        assert!(close(a.area, 1.0) && close(b.area, 0.0));
        transfer(&p, &mut a, &mut b, 5.0);
        assert!(close(a.area, 0.0) && close(b.area, 1.0), "capped at what the giver has");
    }
}
