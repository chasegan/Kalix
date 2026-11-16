# Node Clusters

Status: **design settled; V1a (model and rules) done: `#@cluster` is parsed and indexed,
and the map's menu offers Create, Add, Remove, Rename and Disband, but the map doesn't draw
clusters yet** (stages below). Issue [#476](https://github.com/chasegan/Kalix/issues/476).
The present tense below describes the design. A few choices are marked for checking at
user testing (see Context menu).

Clusters group nodes on the schematic map, so a modeller can fold part of a large model
into one icon and open it again. A cluster has no section of its own: it is a name, in
a `#@cluster` annotation above the header of each node in it (ADR-0010 §6.2). It is
IDE-only: no simulation code reads it (ADR-0010 §1).

```ini
#@cluster = teemburra
[node.teemburra_dam]
type = storage

#@cluster = teemburra
[node.teemburra_offtake]
type = user
```

A modeller works with clusters through the map's context menu: create, add to, remove
from, rename, disband, collapse and expand, one cluster or all. A collapsed cluster can be
dragged like a node, and an expanded one by its name. A toolbar button hides cluster
boxes.

A collapsed cluster draws as one icon at its members' centroid, a small stacked-card
shape with its node count on it and its name below. Links to its members are drawn to the
icon. An expanded cluster draws its members as usual on a translucent grey box, with its
name at the box's top left.

## Terms

- **Member:** a node carrying `#@cluster = <name>`.
- **Loose node:** a node in no cluster.
- **Visible node:** a loose node, or a member of an expanded cluster.
- **Collapsed cluster:** a cluster drawn as one icon.
- **Expanded cluster:** a cluster drawn as its members on a box.
- **Icon:** the shape drawn for a collapsed cluster.
- **Node count:** the number of members, drawn on the icon.
- **Icon label:** the cluster's name, drawn under the icon.
- **Box:** the translucent grey rectangle under an expanded cluster's members.
- **Box name:** the cluster's name, drawn at the box's top left, looking like a node label.
- **Proxy:** a tiny copy of the icon, drawn beside the box name.

## Settled decisions (discussed October and November 2026)

### Rules every case answers to

1. **Clusters are transparent to edits.** An edit to nodes in a cluster either gives
   the result it would give with no cluster there (disband, edit, put the cluster
   back), or isn't offered at all. Never a third result. This covers edits only; how
   clusters are shown is separate. Check any new case against this rule first.

2. **Every edit is one atomic text edit,** so one Ctrl+Z undoes it. Collapse and expand
   aren't edits and aren't undoable.

3. **No confirmations, and every edit shows.** Every edit is one undo away, so nothing
   asks first, including Delete on a collapsed cluster. In return, the modeller must be
   able to see what an edit did, or they won't know to undo it. The one exception is
   with boxes hidden: the modeller has chosen not to see clusters, so a membership edit
   on an expanded cluster doesn't show on the map.

4. **The text is the truth.** Every reparse rebuilds clusters from the text. A cluster
   with no members doesn't exist, so removing the last member removes the cluster
   without any warning. Only nodes are members; a node is in at most one cluster.
   Clusters don't nest (ADR-0010 §6.2 reserves `.` in names for hierarchy later).

### Showing clusters

5. **Which clusters are collapsed is never saved** (ADR-0015 §3, which is
   Proposed). Everything starts collapsed on load, and so does a new cluster, even one
   just made from the map, since people may make several in a row. The nodes turning
   into an icon shows it worked. A three-state collapse button with a remembered
   arrangement was dropped for V1; it may return if user testing misses it.

6. **Showing or hiding boxes is a setting built like node-label mode:** a saved
   preference, applied to every open map, on the toolbar and the menu bar (ADR-0015 §2).
   With boxes hidden, an expanded cluster has no box, box name or proxy, so only its
   members draw and only they can be clicked. Collapsed icons still show. The context
   menu doesn't change: those members still offer `Remove from cluster`.

7. **One box colour.** Fill grey `#808080`, 72% transparent, on all themes, with no theme
   key until Kalix themes are customisable. The border is derived from the fill, moved 35%
   towards black on light themes and white on dark, so the box keeps an edge.

8. **Cluster text follows the map's node-label mode.** In the "names" and "akas only"
   modes, the box name, proxy and icon label show. In the "hidden" mode there is no box
   name or proxy, and the icon label shows on hover, like a node's. The node count is
   always on the icon, like the text inside a node's shape.

9. **No markers for overlapping boxes in V1.** Numbered markers showing which box a node
   belongs to were designed and set aside: telling things apart without colour should be
   solved for Kalix as a whole.

### Selecting

10. **A collapsed cluster is selected when all its members are, and an action on it acts
    on all of them equally.** One rule runs after every selection change and reparse: for
    each collapsed cluster, *all* members selected means the cluster is selected and stays
    collapsed; *some* selected means it expands, so the modeller sees what they selected.
    This covers `Select nodes ▸`, Find…, Show on map, hand edits and undo. The selection is
    never widened to the other members. Collapsing a partly selected cluster deselects its
    members: they were selected some other way, and the modeller may not have intended it.

11. **The box name and proxy stand for the cluster** and behave like the icon: a click
    selects (all the members, when expanded) and takes the editor to the section of the
    cluster's first member in the file; Shift-click adds; a drag moves.

12. **Not offered:**
    - selecting a link drawn to an icon (several real links can share the line; expand
      the cluster instead);
    - drawing a link from or to an icon (a link joins one node to one node);
    - renaming a node through a selected cluster;
    - double-click (nothing on the map uses it, and an accidental one could rewrite the
      model).

### Editing

13. **Rename to an existing name merges** the clusters; the merged cluster keeps the
    target's collapse state and is selected. Renaming a *node* to an existing name stays
    refused: a node name is identity, a cluster is a grouping.

14. **Disband keeps the nodes; Delete deletes them.** Delete on a collapsed cluster deletes
    its members and their links.

15. **Create and Add move members out of their old cluster,** so Create on part of a
    cluster splits it in one step. Create with an existing name joins that cluster.
    Joining a collapsed cluster leaves it collapsed; if it isn't selected, the added nodes
    are deselected, so rule 10 doesn't expand it. Insert node never joins a cluster, even
    inside an expanded box.

16. **Paste keeps the annotation,** so a pasted node joins the cluster of that name. Cut
    then Paste, or a paste into another model, recreates a cluster; Copy then Paste in the
    same model adds to it; a model that already has the name joins it. A paste into a
    collapsed cluster expands it, so the new node can be seen.

17. **Rotate moves locations only,** as if the cluster were disbanded, the nodes rotated
    and the cluster put back. The rotation centre is computed from every member, not from
    the icon. Shapes, boxes and icons stay upright. Rotate needs at least two selected
    nodes, and a collapsed cluster counts as one, so alone it doesn't rotate.

## Context menu

The menu acts on the existing selection: a right-click doesn't change it. Each new item
sits in its ADR-0002 §1 block and is absent when it can't apply (§4).

| Block | Item | Shown when |
|---|---|---|
| ③ Clipboard | Cut, Copy *(existing)* | Unchanged; act on a collapsed cluster's members |
| ④ Selection | `Select nodes ▸ Cluster` | A selected member of an expanded cluster |
| ⑤ Create | `Create cluster…` | Visible nodes selected, and no collapsed cluster selected |
| ⑥ Modify | `Rename` *(existing, node)* | Shown as now; enabled only when the one selected thing is a visible node |
| ⑥ Modify | `Add to "x"`, or `Add to cluster ▸` with several targets | Visible nodes selected, and a target exists |
| ⑥ Modify | `Remove from cluster` | A selected member of an expanded cluster, across any number of clusters |
| ⑥ Modify | `Rename "x"…` | The selection is exactly one whole cluster |
| ⑥ Modify | `Disband cluster(s)`, at the foot of the block | The selection holds a collapsed cluster, or a member of an expanded one |
| ⑦ Destructive | Delete *(existing)* | Unchanged; acts on a collapsed cluster's members |
| ⑧ View / state | `Collapse cluster(s)` | A selected member of an expanded cluster |
| ⑧ View / state | `Expand cluster(s)` | A collapsed cluster selected |
| ⑧ View / state | `Collapse all clusters`, `Expand all clusters` | Every map menu while the model has a cluster. All collapsed: Expand all only. All expanded: Collapse all only. Mixed: both |

Add's targets are the selected collapsed clusters if there are any, otherwise every
cluster except one that already holds all the selected nodes. When a label names a cluster
and a node has the same name, the label adds `(cluster)` after the name:
`Rename "farm_3" (cluster)…`. One helper makes every cluster label (ADR-0003 §2.3).

To check at user testing: the word "Disband"; leaving "cluster" out of labels; Add across
clusters; Create moving members; the `Cluster` child label; the wording of the "all" items.

## Architecture

Nothing is stored that isn't derived from the text at each reparse, except a session map
of collapse state. Parsing, edits, menu rules and the cluster items the real menu holds
are headless-tested; drawing and interaction sit on the existing map panel.

**Parsing** (`ModelParser`, `ModelNode`, `AnnotationKey`). `#@cluster` is read as `#@aka`
is. With two lines on a node the last counts, and the linter says so. Unlike an empty
`#@aka`, an empty or invalid last line counts too: there is no falling back to an earlier
line. A name follows the node-name rule (ADR-0010 §6.2, amended 2026-11-10): ASCII
letters in either case, digits and underscores. `AnnotationKey` checks it for the parser,
the linter and the edits alike, and words the one message that refuses a name; a node with
an invalid name is drawn loose, and the linter says why. A last line with no `=` isn't an
annotation (ADR-0010 §2): the lexer reads it as a comment, so the earlier line counts, and
the linter reports it as malformed. Everything after the `=` is the value (ADR-0010 §2), so
`#@cluster = north # main catchment` names an invalid cluster, not `north` with a comment:
the node is loose, and an edit that puts it in a cluster rewrites the line without the
trailing text.

**The cluster index** (`ClusterIndex`, on `HydrologicalModel`). The model's node map has no
order, so the order of first mention has to be kept from the parse. The index holds each
cluster's members in file order, and is replaced whole at every reparse, so it is a snapshot
of the text. Only complete nodes (with a `type` and a `loc`) count, as only they are on
the map. Names are matched ignoring case, as node names are, so `teemburra` and
`Teemburra` are one cluster, shown with the spelling of its first mention. The index owns
both choices, each in one place: how names are compared (by a key), and which spelling is
shown. The linter warns on each
mention spelled differently from the first, naming it, so a stray spelling is found.

**Edits** (`ClusterEdits`, `CommandExecutor`). Each is a pure function from text to text
edits; `CommandExecutor` is the one place that applies them, once inside
`beginAtomicEdit()`/`endAtomicEdit()`. Create and Add are one function. They read the text
through `NodeSectionLocator`, not the model, so Rename and Disband reach a section that
isn't on the map. An invalid name is refused with an error stating the rule, and never
corrected. Edits match a cluster by its key. Create with a name that exists with other
capitalisation joins that cluster and writes its existing spelling, not the typed one, so
mistyped capitals never rewrite a whole cluster. A Rename that merges into another cluster
does the same. The existing spelling is the index's, so it is found only among clusters
on the map; a name used only on incomplete nodes isn't found, and the typed spelling is
written. A Rename within one cluster changes its spelling by rewriting every member, and
renaming to the spelling already shown tidies any member spelled otherwise. A cluster edit
leaves a node with at most one `#@cluster` line, so a node with two can't fall back to the
earlier cluster: it rewrites the last line in place and deletes any others. Rewriting in
place keeps a comment below the line inside the section, where a delete and re-insert
would push it out. A node with no line gets one directly above its header, below any
comments; those comments belong to the section above, with or without the new line
(ADR-0010 §3), so a later Cut takes the same text plus the annotation. A section starts at
its first annotation (`NodeSectionLocator`), so the clipboard and Delete already carry
`#@cluster`.

**Collapse state** is a session map keyed by `ClusterIndex.key()`, not by a spelling, so a
rename that changes only capitalisation keeps its state; entries are **never removed
when a cluster vanishes**. Undo is the editor's own text undo followed by a reparse, so a
cluster that undo brings back finds its old state. Create and Rename write entries
explicitly: a new name starts collapsed, a rename to a new name copies the old name's
state, and joining an existing cluster leaves its state alone.

**Drawing order** (`MapRenderer`): links; loose nodes; for each cluster in order of its
first mention in the file, its box and then its members; collapsed icons. Loose nodes draw
before all boxes, so one lying inside a box takes its tint; a member draws after its own
box, so its label stays clean of that box, which helps tell the two apart. A member of an
earlier cluster inside a later box is tinted too.

**Box bounds** are computed in screen space on every repaint. Nodes and labels are drawn at
a fixed screen size and only positions scale, so a box's size relative to the map changes
with zoom. Padding has to: keep selection halos clear of the border; keep rounded corners
clear of the contents; cover each member's drawn label, with a small gap; and stay
symmetric about the members. Labels shown only on hover don't widen a box.

**Links.** A link with one end on a member of a collapsed cluster is drawn to that
cluster's icon. A link between two collapsed clusters runs icon to icon; a link within one
collapsed cluster isn't drawn. Links with the same drawn ends in the same direction are
drawn once.

**Hit-testing** (`MapPanel`), in order: icon; node; proxy or box name, topmost first; a
node's link ring (#435); link; empty space. Members of a collapsed cluster and their links
drop out of every hit-test, hover and rectangle selection included. Rectangle selection
picks an icon if the point it is drawn at lies inside. A selection made after an edit waits
for the reparse, as Insert node's does.

**Locations** (`TextCoordinateUpdater.updateNodeCoordinates`). A drag moves each member by
the same offset, as one atomic edit, with dotted ghosts of the members while dragging or
rotating. For now that writes the `loc` property; once the IDE implements ADR-0010 §8, it
writes whichever form each member has.

## Stages

- **V1a: model and rules.** Parsing, the edits, the label helper, the menu rules as pure
  functions with table tests, three lint rules (an invalid name; two `#@cluster` lines; one
  cluster spelled with different capitalisation). A thin slice of the menu, so the edits
  can be tried by hand: Create, Add, Remove, Rename and Disband, with every cluster counted
  as expanded.
- **V1b: drawing.** Boxes, icons, links to icons, collapse state.
- **V1c: interaction.** The rest of the menu (`Select nodes ▸ Cluster`, Collapse, Expand),
  selection, hit-testing, drag, rotate, Delete.
- **V1d: toolbar and menu bar.** The boxes setting and its preference. The last code stage,
  since nothing depends on it.
- **V1e: docs.** A clusters section in `website/docs/docs/ide.md`, a release-note bullet.

## V1 limitations (known, accepted)

- A collapsed icon sits at the centroid, even when its members are far apart. Leave far-off
  nodes out of the cluster.
- Opposite-direction links to an icon overlap as two arrows on one line.
- Where expanded boxes overlap, nothing says which box a node in the overlap belongs to; the
  modeller judges from the shape of the boxes and the network.
- Undo can restore the wrong collapse state: disband an expanded `farm`, create a new `farm`,
  undo twice, and the old `farm` returns collapsed, because the new one wrote over its entry.
- Hand edits change how clusters show: renaming a cluster by find-and-replace makes a name
  with no entry, so it shows collapsed; typing a name passes through partial names at each
  reparse; swapping two names swaps how they show.
- A `#@region` above a member is cut or deleted with it, invisibly when the cluster is
  collapsed. True for single nodes today.

## Later

Overlap markers; box colour per theme; a remembered arrangement for collapse; lint hints
(near-duplicate names, a cluster named like a node); a per-cluster +/− toggle on the map;
nested clusters.
