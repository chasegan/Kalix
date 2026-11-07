//! A user node's account combination: `(smith_co | smith_annual) & valley_cap`.
//!
//! The `accounts` property of a user node is a plain list by default, held by the node as
//! `Vec<usize>` and walked in order of use. A line that uses `|`, `&` or parentheses is a
//! combination, and is held as an `AccountTree` instead: `a | b` draws on `a` then `b` and
//! makes their sum available; `a & b` debits the whole take from each and makes their minimum
//! available. The two never mix at one level without parentheses, so there is no precedence.
//!
//! The parser and the renderer are cold. The folds and walks run per node per step, so the
//! tree is a flat pre-order array of 8-byte terms and the walks recurse over a cursor into it
//! (ADR-0004 §3.2, §3.4). A combination that holds no `&` is a plain list and is lowered to
//! one by the reader, so `a | b` costs what `a, b` costs.

use super::account_manager::AccountManager;

/// One term of a combination, in pre-order. An operator's payload is the number of terms in
/// its subtree, not counting itself, so a walk skips or bounds a subtree by arithmetic.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Term {
    /// An account, by index into the account manager
    Account(u32),
    /// `|`: the subtree's children in order of use; available is their sum
    Any(u32),
    /// `&`: every child in full; available is their minimum
    All(u32),
}

#[derive(Default, Clone, Debug, PartialEq, Eq)]
pub struct AccountTree {
    terms: Vec<Term>,
}

// ---------------------------------------------------------------------------------------------
// Hot path
// ---------------------------------------------------------------------------------------------

/// Post-order fold of the subtree `op` over `inner`: leaves through `leaf`, `|` by sum, `&` by
/// min. The root of a tree is always an operator (`from_combination`).
///
/// A leaf child is folded in the parent's loop. The first two levels are direct calls to
/// functions marked `inline(always)`, so the usual `(a | b) & cap` is one loop with no call,
/// and a deeper tree recurses from the third level. The loop body is one macro, expanded once
/// per level, because the compiler declined to inline it as a closure or as a function passed
/// by reference. Generic in the leaf operation so that each use inlines it.
macro_rules! fold_body {
    ($op:expr, $inner:expr, $leaf:expr, $next:ident) => {{
        let mut rest: &[Term] = $inner;
        match $op {
            Term::Any(_) => {
                let mut total = 0.0;
                while let Some((&child, after)) = rest.split_first() {
                    total += match child {
                        Term::Account(idx) => { rest = after; $leaf(idx as usize) }
                        _ => { let (inner, after) = after.split_at(subtree_len(child)); rest = after; $next(child, inner, $leaf) }
                    };
                }
                total
            }
            Term::All(_) => {
                let mut least = f64::INFINITY;
                while let Some((&child, after)) = rest.split_first() {
                    least = least.min(match child {
                        Term::Account(idx) => { rest = after; $leaf(idx as usize) }
                        _ => { let (inner, after) = after.split_at(subtree_len(child)); rest = after; $next(child, inner, $leaf) }
                    });
                }
                least
            }
            Term::Account(_) => {
                debug_assert!(false, "an account tree's root is an operator");
                0.0
            }
        }
    }};
}

/// Top-down walk of the subtree `op` over `inner` with `amount`. `|` hands each child what the
/// children before it left. `&` takes the least of `amount` and what its sides can cover, by
/// `avail`, and hands that to every side; so under a `|` it reports what it absorbed and the
/// `|` carries the rest on. Returns what the subtree absorbed. Laid out as `fold`: leaves in
/// the parent's loop, two levels inlined by direct call, the body one macro.
macro_rules! walk_body {
    ($op:expr, $inner:expr, $amount:expr, $am:expr, $avail:expr, $leaf:expr, $next:ident) => {{
        match $op {
            Term::Any(_) => {
                let mut rest: &[Term] = $inner;
                let mut remaining = $amount;
                while let Some((&child, after)) = rest.split_first() {
                    if remaining <= 0.0 {
                        break;
                    }
                    remaining -= match child {
                        Term::Account(idx) => { rest = after; $leaf($am, idx as usize, remaining) }
                        _ => { let (inner, after) = after.split_at(subtree_len(child)); rest = after; $next(child, inner, remaining, $am, $avail, $leaf) }
                    };
                }
                $amount - remaining
            }
            Term::All(_) => {
                let capacity = fold($op, $inner, &|idx| $avail(&*$am, idx));
                let take = $amount.min(capacity);
                each_side_body!($inner, take, $am, $avail, $leaf, $next);
                take
            }
            Term::Account(_) => {
                debug_assert!(false, "an account tree's root is an operator");
                0.0
            }
        }
    }};
}

/// Hand `amount` to every side of an `&` whose sides are `inner`, an operator side through
/// `$next`. The caller has made sure each side can cover it.
macro_rules! each_side_body {
    ($inner:expr, $amount:expr, $am:expr, $avail:expr, $leaf:expr, $next:ident) => {{
        let mut rest: &[Term] = $inner;
        while let Some((&side, after)) = rest.split_first() {
            match side {
                Term::Account(idx) => { rest = after; $leaf($am, idx as usize, $amount); }
                _ => { let (inner, after) = after.split_at(subtree_len(side)); rest = after; $next(side, inner, $amount, $am, $avail, $leaf); }
            }
        }
    }};
}

impl AccountTree {
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.terms.is_empty()
    }

    /// Sum of the balances of the accounts the tree can draw on, each floored at zero and
    /// combined by the tree: `|` sums, `&` takes the minimum.
    #[inline(always)]
    pub fn balance(&self, account_manager: &AccountManager) -> f64 {
        let (op, inner, _) = first_subtree(&self.terms);
        fold(op, inner, &|idx| balance_of(account_manager, idx))
    }

    /// As `balance`, over what each account can still cover: its balance less the orders
    /// already committed against it, floored at zero account by account.
    #[inline(always)]
    pub fn uncommitted(&self, account_manager: &AccountManager) -> f64 {
        let (op, inner, _) = first_subtree(&self.terms);
        fold(op, inner, &|idx| uncommitted_of(account_manager, idx))
    }

    /// Debit a take: left to right through `|`, filling each account before the next, and in
    /// full from every side of `&`. The take must not exceed `balance()`, and is then absorbed
    /// exactly: the root `&` hands every side the whole take, and a nested `&` takes the least
    /// of what it is handed and what its sides can cover.
    #[inline(always)]
    pub fn debit(&self, account_manager: &mut AccountManager, amount: f64) {
        if amount <= 0.0 {
            return;
        }
        let (op, inner, _) = first_subtree(&self.terms);
        let leaf = &mut |account_manager: &mut AccountManager, idx: usize, remaining: f64| {
            let take = remaining.min(balance_of(account_manager, idx));
            if take > 0.0 {
                account_manager.debit_account(idx, take);
            }
            take
        };
        match op {
            Term::All(_) => each_side_body!(inner, amount, account_manager, &balance_of, leaf, walk),
            _ => { walk(op, inner, amount, account_manager, &balance_of, leaf); }
        }
    }

    /// Commit an accepted order, as `debit` against the uncommitted balances. The order may
    /// exceed `uncommitted()` where order_accounts authorise the rest; what a side of the
    /// root `&`, or the whole, cannot cover goes on its first account, which is where the
    /// delivery will be debited first. That is the plain list's rule, applied per side.
    #[inline(always)]
    pub fn commit(&self, account_manager: &mut AccountManager, amount: f64, due_in: usize) {
        if amount <= 0.0 {
            return;
        }
        let (op, inner, _) = first_subtree(&self.terms);
        let leaf = &mut |account_manager: &mut AccountManager, idx: usize, remaining: f64| {
            let commit = remaining.min(uncommitted_of(account_manager, idx));
            if commit > 0.0 {
                account_manager.commit_account(idx, commit, due_in);
            }
            commit
        };
        match op {
            Term::All(_) => {
                let mut rest = inner;
                while let Some((&side, after)) = rest.split_first() {
                    match side {
                        Term::Account(idx) => {
                            rest = after;
                            account_manager.commit_account(idx as usize, amount, due_in);
                        }
                        _ => {
                            let (side_inner, after) = after.split_at(subtree_len(side));
                            rest = after;
                            let taken = walk(side, side_inner, amount, account_manager, &uncommitted_of, leaf);
                            if taken < amount {
                                account_manager.commit_account(first_account(side_inner), amount - taken, due_in);
                            }
                        }
                    }
                }
            }
            _ => {
                let taken = walk(op, inner, amount, account_manager, &uncommitted_of, leaf);
                if taken < amount {
                    account_manager.commit_account(first_account(inner), amount - taken, due_in);
                }
            }
        }
    }
}

#[inline(always)]
fn balance_of(account_manager: &AccountManager, idx: usize) -> f64 {
    account_manager.get_account_balance(idx).max(0.0)
}

#[inline(always)]
fn uncommitted_of(account_manager: &AccountManager, idx: usize) -> f64 {
    account_manager.get_account_uncommitted_balance(idx)
}

/// A subtree is its operator followed by the `len` terms beneath it, so the terms beneath an
/// operator are read off the slice after it. The split is the one bounds check an operator
/// costs; the leaves are then read without one.
#[inline(always)]
fn subtree_len(op: Term) -> usize {
    match op {
        Term::Account(_) => 0,
        Term::Any(len) | Term::All(len) => len as usize,
    }
}

/// The subtree at the head of `terms`: its operator, the terms beneath it, and what follows.
#[inline(always)]
fn first_subtree(terms: &[Term]) -> (Term, &[Term], &[Term]) {
    let op = terms[0];
    let (inner, after) = terms[1..].split_at(subtree_len(op));
    (op, inner, after)
}


#[inline(always)]
fn fold<F: Fn(usize) -> f64>(op: Term, inner: &[Term], leaf: &F) -> f64 {
    fold_body!(op, inner, leaf, fold_second)
}

#[inline(always)]
fn fold_second<F: Fn(usize) -> f64>(op: Term, inner: &[Term], leaf: &F) -> f64 {
    fold_body!(op, inner, leaf, fold_deeper)
}

fn fold_deeper<F: Fn(usize) -> f64>(op: Term, inner: &[Term], leaf: &F) -> f64 {
    fold_body!(op, inner, leaf, fold_deeper)
}



#[inline(always)]
fn walk<A, L>(op: Term, inner: &[Term], amount: f64, account_manager: &mut AccountManager, avail: &A, leaf: &mut L) -> f64
where A: Fn(&AccountManager, usize) -> f64, L: FnMut(&mut AccountManager, usize, f64) -> f64 {
    walk_body!(op, inner, amount, account_manager, avail, leaf, walk_second)
}

#[inline(always)]
fn walk_second<A, L>(op: Term, inner: &[Term], amount: f64, account_manager: &mut AccountManager, avail: &A, leaf: &mut L) -> f64
where A: Fn(&AccountManager, usize) -> f64, L: FnMut(&mut AccountManager, usize, f64) -> f64 {
    walk_body!(op, inner, amount, account_manager, avail, leaf, walk_deeper)
}

fn walk_deeper<A, L>(op: Term, inner: &[Term], amount: f64, account_manager: &mut AccountManager, avail: &A, leaf: &mut L) -> f64
where A: Fn(&AccountManager, usize) -> f64, L: FnMut(&mut AccountManager, usize, f64) -> f64 {
    walk_body!(op, inner, amount, account_manager, avail, leaf, walk_deeper)
}

/// The first account in a subtree: its leftmost leaf.
fn first_account(sub: &[Term]) -> usize {
    sub.iter()
        .find_map(|term| match term { Term::Account(idx) => Some(*idx as usize), _ => None })
        .expect("an account tree holds at least one account")
}

// ---------------------------------------------------------------------------------------------
// Cold path: build, inspect, render
// ---------------------------------------------------------------------------------------------

/// A parsed combination before lowering: the shape of the text, with accounts resolved.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Combination {
    Account(usize),
    Any(Vec<Combination>),
    All(Vec<Combination>),
}

/// What the reader makes of an `accounts` line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AccountsProperty {
    /// A plain list, or a combination without `&`, which is the same thing
    List(Vec<usize>),
    /// A combination with at least one `&`
    Tree(AccountTree),
}

impl AccountTree {
    /// The accounts in the tree, in order of first use.
    pub fn accounts(&self) -> impl Iterator<Item = usize> + '_ {
        self.terms.iter().filter_map(|t| match t {
            Term::Account(idx) => Some(*idx as usize),
            _ => None,
        })
    }

    pub fn terms(&self) -> &[Term] {
        &self.terms
    }

    /// Flatten a combination to pre-order. Adjacent levels of one operator are merged by
    /// `Combination::canonical`; the tree is laid out as given, except that a lone account is
    /// wrapped in a `|` of one, so that the root is always an operator (the folds rely on it).
    pub fn from_combination(combination: &Combination) -> Self {
        if let Combination::Account(_) = combination {
            return Self::from_combination(&Combination::Any(vec![combination.clone()]));
        }
        fn push(terms: &mut Vec<Term>, c: &Combination) {
            match c {
                Combination::Account(idx) => terms.push(Term::Account(*idx as u32)),
                Combination::Any(children) | Combination::All(children) => {
                    let at = terms.len();
                    terms.push(Term::Any(0));
                    for child in children {
                        push(terms, child);
                    }
                    let len = (terms.len() - at - 1) as u32;
                    terms[at] = match c {
                        Combination::Any(_) => Term::Any(len),
                        _ => Term::All(len),
                    };
                }
            }
        }
        let mut terms = Vec::new();
        push(&mut terms, combination);
        AccountTree { terms }
    }

    /// The combination back from the tree, for rendering.
    pub fn to_combination(&self) -> Combination {
        fn read(terms: &[Term], cursor: &mut usize) -> Combination {
            let term = terms[*cursor];
            *cursor += 1;
            match term {
                Term::Account(idx) => Combination::Account(idx as usize),
                Term::Any(len) | Term::All(len) => {
                    let end = *cursor + len as usize;
                    let mut children = Vec::new();
                    while *cursor < end {
                        children.push(read(terms, cursor));
                    }
                    match term {
                        Term::Any(_) => Combination::Any(children),
                        _ => Combination::All(children),
                    }
                }
            }
        }
        let mut cursor = 0;
        read(&self.terms, &mut cursor)
    }

    /// Render in the one canonical form: `|` and `&` with single spaces, parentheses only
    /// where a level's operator differs from its parent's.
    pub fn render(&self, account_manager: &AccountManager) -> String {
        fn render(c: &Combination, parent_is_all: Option<bool>, account_manager: &AccountManager) -> String {
            match c {
                Combination::Account(idx) => account_manager.get_account(*idx)
                    .map(|a| a.name.clone())
                    .unwrap_or_default(),
                Combination::Any(children) | Combination::All(children) => {
                    let is_all = matches!(c, Combination::All(_));
                    let op = if is_all { " & " } else { " | " };
                    let inner = children.iter()
                        .map(|child| render(child, Some(is_all), account_manager))
                        .collect::<Vec<_>>()
                        .join(op);
                    match parent_is_all {
                        Some(p) if p != is_all => format!("({})", inner),
                        _ => inner,
                    }
                }
            }
        }
        render(&self.to_combination(), None, account_manager)
    }
}

impl Combination {
    /// Merge a level into a parent of the same operator and unwrap levels of one child, so
    /// that `a | (b | c)` is `a | b | c` and `(a)` is `a`. The result has no redundant
    /// structure, which is what makes the rendering canonical.
    pub fn canonical(self) -> Combination {
        let (is_all, children) = match self {
            Combination::Account(idx) => return Combination::Account(idx),
            Combination::Any(children) => (false, children),
            Combination::All(children) => (true, children),
        };
        let mut flat = Vec::with_capacity(children.len());
        for child in children {
            match (is_all, child.canonical()) {
                (false, Combination::Any(grandchildren)) | (true, Combination::All(grandchildren)) => flat.extend(grandchildren),
                (_, other) => flat.push(other),
            }
        }
        match (flat.len(), is_all) {
            (1, _) => flat.pop().unwrap(),
            (_, true) => Combination::All(flat),
            (_, false) => Combination::Any(flat),
        }
    }

    fn has_all(&self) -> bool {
        match self {
            Combination::Account(_) => false,
            Combination::Any(children) => children.iter().any(|c| c.has_all()),
            Combination::All(_) => true,
        }
    }

    fn accounts_in_order(&self, out: &mut Vec<usize>) {
        match self {
            Combination::Account(idx) => out.push(*idx),
            Combination::Any(children) | Combination::All(children) => {
                for child in children {
                    child.accounts_in_order(out);
                }
            }
        }
    }
}

/// The error for a name in an `accounts` line that is not an account. One wording for the
/// plain list and the combination.
pub fn unknown_account_message(name: &str, account_manager: &AccountManager) -> String {
    if account_manager.get_group_idx(name).is_some() {
        format!("'{}' is an account group; a node's 'accounts' takes account names \
            (group targeting belongs in [ras.*] sections)", name)
    } else {
        format!("Unknown account '{}'. Accounts are declared in [acc.*] sections.", name)
    }
}

/// The error for an account named twice in one `accounts` line.
pub fn duplicate_account_message(name: &str) -> String {
    format!("Account '{}' appears more than once; an account may appear once in a node's 'accounts'", name)
}

/// Whether an `accounts` line is a combination rather than a plain list.
pub fn is_combination(line: &str) -> bool {
    line.contains(['(', ')', '|', '&'])
}

/// Parse a combination line into what the node holds. The plain-list path is the caller's:
/// this is called only when `is_combination` is true. Errors say what is wrong with the line,
/// and the caller names the node and the line number.
pub fn parse_combination(line: &str, account_manager: &AccountManager) -> Result<AccountsProperty, String> {
    if line.contains(',') {
        return Err("a comma cannot be mixed with '|', '&' or parentheses: write the whole line as a combination, \
            using '|' where a plain list uses ','".to_string());
    }
    if line.contains("&&") {
        return Err("'&&' is not an account operator; write a single '&' (accounts combine, they are not conditions)".to_string());
    }
    if line.contains("||") {
        return Err("'||' is not an account operator; write a single '|' (accounts combine, they are not conditions)".to_string());
    }
    let tokens = tokenise(line)?;
    let mut parser = Parser { tokens: &tokens, pos: 0, account_manager };
    let combination = parser.level()?;
    if parser.pos < tokens.len() {
        return Err(match tokens[parser.pos] {
            Token::Close => "unmatched ')'".to_string(),
            ref other => format!("unexpected '{}'", other),
        });
    }
    let combination = combination.canonical();

    let mut seen = Vec::new();
    combination.accounts_in_order(&mut seen);
    for (i, idx) in seen.iter().enumerate() {
        if seen[..i].contains(idx) {
            let name = account_manager.get_account(*idx).map(|a| a.name.as_str()).unwrap_or("?");
            return Err(duplicate_account_message(name));
        }
    }

    if combination.has_all() {
        Ok(AccountsProperty::Tree(AccountTree::from_combination(&combination)))
    } else {
        Ok(AccountsProperty::List(seen))
    }
}

#[derive(Debug, PartialEq, Eq)]
enum Token {
    Name(String),
    Open,
    Close,
    Any,
    All,
}

impl std::fmt::Display for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Token::Name(name) => write!(f, "{}", name),
            Token::Open => write!(f, "("),
            Token::Close => write!(f, ")"),
            Token::Any => write!(f, "|"),
            Token::All => write!(f, "&"),
        }
    }
}

fn tokenise(line: &str) -> Result<Vec<Token>, String> {
    let mut tokens = Vec::new();
    let mut chars = line.char_indices().peekable();
    while let Some((i, c)) = chars.next() {
        match c {
            ' ' | '\t' => {}
            '(' => tokens.push(Token::Open),
            ')' => tokens.push(Token::Close),
            '|' => tokens.push(Token::Any),
            '&' => tokens.push(Token::All),
            c if c.is_ascii_alphanumeric() || c == '_' => {
                let mut end = i + c.len_utf8();
                while let Some(&(j, d)) = chars.peek() {
                    if d.is_ascii_alphanumeric() || d == '_' {
                        end = j + d.len_utf8();
                        chars.next();
                    } else {
                        break;
                    }
                }
                tokens.push(Token::Name(line[i..end].to_lowercase()));
            }
            other => return Err(format!("unexpected character '{}'; a combination holds account names, '|', '&' and parentheses", other)),
        }
    }
    Ok(tokens)
}

struct Parser<'a> {
    tokens: &'a [Token],
    pos: usize,
    account_manager: &'a AccountManager,
}

impl<'a> Parser<'a> {
    /// `level := term (op term)*`, one operator per level
    fn level(&mut self) -> Result<Combination, String> {
        let first = self.term()?;
        let mut op: Option<&Token> = None;
        let mut children = vec![first];
        while let Some(token) = self.tokens.get(self.pos) {
            match token {
                Token::Any | Token::All => {
                    match op {
                        None => op = Some(token),
                        Some(prev) if prev != token => {
                            return Err("'|' and '&' cannot be mixed without parentheses: write (a | b) & c or a | (b & c)".to_string());
                        }
                        _ => {}
                    }
                    self.pos += 1;
                    children.push(self.term()?);
                }
                _ => break,
            }
        }
        Ok(match op {
            None => children.pop().unwrap(),
            Some(Token::All) => Combination::All(children),
            Some(_) => Combination::Any(children),
        })
    }

    /// `term := name | '(' level ')'`
    fn term(&mut self) -> Result<Combination, String> {
        match self.tokens.get(self.pos) {
            Some(Token::Name(name)) => {
                self.pos += 1;
                let idx = self.account_manager.get_account_idx(name)
                    .ok_or_else(|| unknown_account_message(name, self.account_manager))?;
                Ok(Combination::Account(idx))
            }
            Some(Token::Open) => {
                self.pos += 1;
                let inner = self.level()?;
                match self.tokens.get(self.pos) {
                    Some(Token::Close) => {
                        self.pos += 1;
                        Ok(inner)
                    }
                    _ => Err("missing ')'".to_string()),
                }
            }
            Some(Token::Close) => Err("empty parentheses or a missing account name before ')'".to_string()),
            Some(token) => Err(format!("expected an account name or '(' before '{}'", token)),
            None => Err("the line ends where an account name was expected".to_string()),
        }
    }
}
