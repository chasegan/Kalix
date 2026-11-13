//! The soil under a field, kept as layers so that water below one crop's roots
//! is still there for the next. The reasoning and the references are in the
//! field page of the user docs (website/docs/docs/field.md, "How the node
//! works" and "Where each part comes from"): FAO-56's root zone does not drain
//! below field capacity, and USDA-ARS's pyfao56 keeps the same depletion
//! below the roots to the deepest they will reach.
//!
//! The layers' boundaries are the field's full root depths, fallow included.
//! Each partition of the field (the fallow, and each crop in the ground) holds
//! one bucket for its root zone, which the plants and the irrigator work, and
//! one depletion per layer below it, which only overflow, root growth and
//! transfers touch. A root zone that is still growing ends inside a layer; the
//! part of that layer below the root front is kept as its own depletion, and
//! the roots take the layer in as they reach down through it.
//!
//! Everything is in mm of water over the partition's area; a depletion is how
//! far below full: 0 full, the capacity empty.

/// One more than the crop slots a field can hold: each slot's crop, and the fallow
pub const MAX_LAYERS: usize = crate::nodes::field_node::MAX_CROPS + 1;

/// The layering a field's soil is kept in: the same for every partition
#[derive(Clone, Default)]
pub struct Profile {
    pub n_layers: usize,
    /// mm of water per metre of soil, between full and empty
    pub available_water: f64,
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
        let mut profile = Profile { n_layers: depths.len(), available_water, ..Default::default() };
        let mut top = 0.0;
        for (i, depth) in depths.iter().enumerate() {
            profile.bottom[i] = *depth;
            profile.capacity[i] = available_water * (depth - top) / 1000.0;
            top = *depth;
        }
        profile
    }

    /// How many layers a root zone of this depth covers whole
    pub fn root_layers(&self, root_depth: f64) -> usize {
        self.bottom[..self.n_layers].iter().filter(|b| **b <= root_depth).count()
    }

    /// mm of water a depth of soil holds
    fn capacity_of(&self, depth_mm: f64) -> f64 {
        self.available_water * depth_mm / 1000.0
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
    /// mm, how deep the roots reach today
    pub root_depth: f64,
    /// The root bucket covers layers 0..root_layers whole, and the top of
    /// layer root_layers down to root_depth when the roots end inside it
    pub root_layers: usize,
    /// mm, what the root zone holds between full and empty
    pub root_capacity: f64,
    /// mm below full, over the root zone
    pub root_depletion: f64,
    /// mm below full, per layer, of the part of the layer below the roots:
    /// the whole layer from root_layers + 1 down, and for layer root_layers the
    /// part below the root front. Meaningless above the roots.
    pub layer_depletion: [f64; MAX_LAYERS],
    /// mm, how far the surface evaporation layer is below full (FAO-56 Chapter 7's
    /// De), 0 to the field's TEW. Bookkeeping within the root bucket for a crop
    /// with a basal coefficient; unused by a single-coefficient crop.
    pub evap_depletion: f64,
}

impl Partition {
    /// A partition rooted to `root_depth`, every layer `initial_depletion` mm
    /// below full over the whole profile, spread by capacity.
    pub fn new(profile: &Profile, root_depth: f64, area: f64, initial_depletion: f64) -> Partition {
        let fraction = initial_depletion / profile.total_capacity();
        let mut partition = Partition {
            area,
            root_depth,
            root_layers: profile.root_layers(root_depth),
            root_capacity: profile.capacity_of(root_depth),
            root_depletion: 0.0,
            layer_depletion: [0.0; MAX_LAYERS],
            evap_depletion: 0.0,
        };
        partition.root_depletion = fraction * partition.root_capacity;
        for i in partition.root_layers..profile.n_layers {
            partition.layer_depletion[i] = fraction * partition.unrooted_capacity(profile, i);
        }
        partition
    }

    /// mm of water the part of layer `i` below the roots holds: the whole layer
    /// below the root zone, the part below the root front in the layer the roots
    /// end in, nothing in a layer the roots cover
    fn unrooted_capacity(&self, profile: &Profile, i: usize) -> f64 {
        if i < self.root_layers {
            0.0
        } else if i == self.root_layers {
            profile.capacity_of(profile.bottom[i] - self.root_depth)
        } else {
            profile.capacity[i]
        }
    }

    /// The roots reach down to `depth` mm. The soil they grow into joins the
    /// root bucket with the water it holds, at the wetness of the part of the
    /// layer it comes from. Roots never shrink: a smaller depth does nothing.
    pub fn grow(&mut self, profile: &Profile, depth: f64) {
        let depth = depth.min(profile.bottom[profile.n_layers - 1]);
        while depth > self.root_depth {
            let i = self.root_layers;
            let to = depth.min(profile.bottom[i]);
            let unrooted = self.unrooted_capacity(profile, i);
            let taking = profile.capacity_of(to - self.root_depth);
            let moved = if unrooted > 0.0 { self.layer_depletion[i] * (taking / unrooted).min(1.0) } else { 0.0 };
            self.root_depletion += moved;
            self.layer_depletion[i] -= moved;
            self.root_capacity += taking;
            self.root_depth = to;
            if to >= profile.bottom[i] {
                self.layer_depletion[i] = 0.0;
                self.root_layers += 1;
            }
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

    /// The whole profile as one depletion per layer: the root bucket spread
    /// uniformly over the soil it covers, every part as wet as the bucket, with
    /// the layer the roots end in adding what sits below the front; the layers
    /// below the roots as they are
    fn spread(&self, profile: &Profile) -> [f64; MAX_LAYERS] {
        let mut layers = self.layer_depletion;
        let fraction = if self.root_capacity > 0.0 { self.root_depletion / self.root_capacity } else { 0.0 };
        for i in 0..self.root_layers {
            layers[i] = fraction * profile.capacity[i];
        }
        let i = self.root_layers;
        if i < profile.n_layers {
            let rooted = profile.capacity[i] - self.unrooted_capacity(profile, i);
            layers[i] = fraction * rooted + self.layer_depletion[i];
        }
        layers
    }

    /// The reverse: the soil the roots cover pooled into the bucket. A layer
    /// the roots end in is taken to be as wet below the front as above it.
    fn pool(&mut self, profile: &Profile, layers: [f64; MAX_LAYERS]) {
        self.layer_depletion = layers;
        self.root_depletion = layers[..self.root_layers].iter().sum();
        let i = self.root_layers;
        if i < profile.n_layers && profile.capacity[i] > 0.0 {
            let unrooted = self.unrooted_capacity(profile, i);
            let below = layers[i] * unrooted / profile.capacity[i];
            self.root_depletion += layers[i] - below;
            self.layer_depletion[i] = below;
        }
    }
}

/// Moves `area` km2 from one partition to another, with its water: the two are
/// spread over the layers, mixed layer by layer in proportion to area, and the
/// receiver's root zone pooled again. The giver's water per mm does not
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
    to.evap_depletion = (to.area * to.evap_depletion + area * from.evap_depletion) / new_area;
    to.pool(profile, to_layers);
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
        assert_eq!(p.root_layers(200.0), 0, "roots ending inside the first layer cover none whole");
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
        // Roots ending inside the first layer: 200 of 600 mm is a third of it
        let seedling = Partition::new(&p, 200.0, 1.0, 27.0);
        assert_eq!(seedling.root_layers, 0);
        assert!(close(seedling.root_capacity, 30.0));
        assert!(close(seedling.root_depletion, 6.0), "a fifth of 27 in the top 30 of 135");
        assert!(close(seedling.layer_depletion[0], 12.0), "the rest of the first layer, 60 of 135");
        assert!(close(seedling.layer_depletion[1], 9.0));
        assert!(close(seedling.water(&p), 108.0));
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
        // Roots inside the first layer: the part below the front takes overflow first
        let mut seedling = Partition::new(&p, 200.0, 1.0, 0.0);
        seedling.layer_depletion[0] = 10.0;
        seedling.layer_depletion[1] = 10.0;
        assert!(close(seedling.drain_overflow(&p, 15.0), 0.0));
        assert!(close(seedling.layer_depletion[0], 0.0) && close(seedling.layer_depletion[1], 5.0));
    }

    #[test]
    fn roots_grow_into_the_soil_below_and_take_its_water_with_them() {
        let p = profile();
        // A seedling at 200 mm with a dry-ish bucket over soil that is wetter below
        let mut crop = Partition::new(&p, 200.0, 1.0, 0.0);
        crop.root_depletion = 12.0;      // 40% of 30
        crop.layer_depletion[0] = 6.0;   // 10% of the 60 below the front in layer 0
        crop.layer_depletion[1] = 9.0;   // 20% of layer 1
        let before = crop.water(&p);
        // Half way through the first layer: 200 mm more, half of the 400 mm below the front
        crop.grow(&p, 400.0);
        assert!(close(crop.root_depth, 400.0) && close(crop.root_capacity, 60.0));
        assert!(close(crop.root_depletion, 12.0 + 3.0), "takes half of the 6 mm");
        assert!(close(crop.layer_depletion[0], 3.0));
        assert_eq!(crop.root_layers, 0);
        assert!(close(crop.water(&p), before), "growth moves water, never makes or loses it");
        // Across the boundary and into the second layer
        crop.grow(&p, 750.0);
        assert_eq!(crop.root_layers, 1);
        assert!(close(crop.root_capacity, 112.5));
        assert!(close(crop.root_depletion, 15.0 + 3.0 + 4.5), "the rest of layer 0 and half of layer 1's 9");
        assert!(close(crop.layer_depletion[1], 4.5));
        assert!(close(crop.water(&p), before));
        // To the bottom, and no further; shrinking does nothing
        crop.grow(&p, 1500.0);
        assert_eq!(crop.root_layers, 2);
        assert!(close(crop.root_depth, 900.0) && close(crop.root_capacity, 135.0));
        assert!(close(crop.root_depletion, 27.0) && close(crop.water(&p), before));
        crop.grow(&p, 300.0);
        assert!(close(crop.root_depth, 900.0));
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
    fn a_transfer_to_roots_that_end_inside_a_layer_splits_the_layer_at_the_front() {
        let p = profile();
        // The fallow's top layer is 30 below full (a third of 90), its bottom full.
        // A seedling partition at 200 mm takes land: its bucket is the top 30 mm of
        // capacity, which must come out a third down too.
        let mut fallow = Partition::new(&p, 600.0, 3.0, 0.0);
        fallow.root_depletion = 30.0;
        let mut crop = Partition::new(&p, 200.0, 0.0, 0.0);
        let before = fallow.area * fallow.water(&p);
        transfer(&p, &mut fallow, &mut crop, 1.0);
        assert!(close(crop.root_depletion, 10.0), "a third of the bucket's 30");
        assert!(close(crop.layer_depletion[0], 20.0), "a third of the 60 below the front");
        assert!(close(crop.layer_depletion[1], 0.0));
        assert!(close(fallow.area * fallow.water(&p) + crop.area * crop.water(&p), before));
        // And back again at harvest, the water returning layer by layer
        crop.root_depletion = 30.0; // the seedling's bucket emptied
        let before = fallow.area * fallow.water(&p) + crop.area * crop.water(&p);
        transfer(&p, &mut crop, &mut fallow, 1.0);
        assert!(close(crop.area, 0.0) && close(fallow.area, 3.0));
        // Layer 0 from the crop: 30 + 20 = 50; mixed 2:1 with the fallow's 30 -> 36.667
        assert!(close(fallow.root_depletion, (2.0 * 30.0 + 50.0) / 3.0));
        assert!(close(fallow.area * fallow.water(&p), before));
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
