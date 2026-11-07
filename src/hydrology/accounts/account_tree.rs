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

impl AccountTree {
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.terms.is_empty()
    }

    /// Sum of the balances of the accounts the tree can draw on, each floored at zero and
    /// combined by the tree: `|` sums, `&` takes the minimum.
    #[inline]
    pub fn balance(&self, account_manager: &AccountManager) -> f64 {
        fold(&self.terms, &|idx| account_manager.get_account_balance(idx).max(0.0))
    }

    /// As `balance`, over what each account can still cover: its balance less the orders
    /// already committed against it, floored at zero account by account.
    #[inline]
    pub fn uncommitted(&self, account_manager: &AccountManager) -> f64 {
        fold(&self.terms, &|idx| account_manager.get_account_uncommitted_balance(idx))
    }

    /// Debit a take: left to right through `|`, filling each account before the next, and in
    /// full from every side of `&`. A take capped at `balance()` is absorbed exactly.
    #[inline]
    pub fn debit(&self, account_manager: &mut AccountManager, amount: f64) {
        walk(&self.terms, amount, &mut |idx, remaining| {
            let take = remaining.min(account_manager.get_account_balance(idx).max(0.0));
            if take > 0.0 {
                account_manager.debit_account(idx, take);
            }
            take
        });
    }

    /// Commit an accepted order, as `debit` against the uncommitted balances. What the tree
    /// cannot cover (an order the order_accounts authorised) goes on the first account of
    /// each side that fell short, which is where its delivery will be debited first: the
    /// plain list's rule, applied to every `&` side and to the whole.
    #[inline]
    pub fn commit(&self, account_manager: &mut AccountManager, amount: f64, due_in: usize) {
        commit_full(&self.terms, amount, account_manager, due_in);
    }
}

/// A subtree is a slice: its operator first, then the `len` terms beneath it. `children`
/// splits a subtree that starts at the head of `terms` from what follows it. That split is
/// the one bounds check an operator costs; the leaves under it are then read without one.
#[inline(always)]
fn children(terms: &[Term]) -> (&[Term], &[Term]) {
    let len = match terms[0] {
        Term::Account(_) => 0,
        Term::Any(len) | Term::All(len) => len as usize,
    };
    terms[1..].split_at(len)
}

/// Post-order fold of the operator subtree `sub` (its operator first): leaves through `leaf`,
/// `|` by sum, `&` by min. The root of a tree is always an operator (`from_combination`).
///
/// A leaf child is folded in the parent's loop, and the first two levels are inlined, so the
/// usual `(a | b) & cap` is one loop with no call; a deeper tree recurses from the third
/// level. Generic in the leaf operation so that each use inlines it.
#[inline(always)]
fn fold<F: Fn(usize) -> f64>(sub: &[Term], leaf: &F) -> f64 {
    fold_level(sub, leaf, &|sub, leaf| fold_level(sub, leaf, &fold_deeper))
}

fn fold_deeper<F: Fn(usize) -> f64>(sub: &[Term], leaf: &F) -> f64 {
    fold_level(sub, leaf, &fold_deeper)
}

#[inline(always)]
fn fold_level<F: Fn(usize) -> f64, S: Fn(&[Term], &F) -> f64>(sub: &[Term], leaf: &F, subtree: &S) -> f64 {
    let (mut rest, _) = children(sub);
    match sub[0] {
        Term::Any(_) => {
            let mut total = 0.0;
            while let Some((&child, after)) = rest.split_first() {
                total += match child {
                    Term::Account(idx) => { rest = after; leaf(idx as usize) }
                    _ => { let (inner, after) = children(rest); let value = subtree(&rest[..inner.len() + 1], leaf); rest = after; value }
                };
            }
            total
        }
        Term::All(_) => {
            let mut least = f64::INFINITY;
            while let Some((&child, after)) = rest.split_first() {
                least = least.min(match child {
                    Term::Account(idx) => { rest = after; leaf(idx as usize) }
                    _ => { let (inner, after) = children(rest); let value = subtree(&rest[..inner.len() + 1], leaf); rest = after; value }
                });
            }
            least
        }
        Term::Account(_) => {
            debug_assert!(false, "an account tree's root is an operator");
            0.0
        }
    }
}

/// Top-down walk of the operator subtree `sub` with `amount`: `|` hands each child what the
/// children before it left, `&` hands every child the whole amount. Returns what the subtree
/// absorbed. Laid out as `fold`: leaves in the parent's loop, two levels inlined.
#[inline(always)]
fn walk<F: FnMut(usize, f64) -> f64>(sub: &[Term], amount: f64, leaf: &mut F) -> f64 {
    walk_level(sub, amount, leaf, &|sub, amount, leaf| walk_level(sub, amount, leaf, &walk_deeper))
}

fn walk_deeper<F: FnMut(usize, f64) -> f64>(sub: &[Term], amount: f64, leaf: &mut F) -> f64 {
    walk_level(sub, amount, leaf, &walk_deeper)
}

#[inline(always)]
fn walk_level<F: FnMut(usize, f64) -> f64, S: Fn(&[Term], f64, &mut F) -> f64>(sub: &[Term], amount: f64, leaf: &mut F, subtree: &S) -> f64 {
    let (mut rest, _) = children(sub);
    match sub[0] {
        Term::Any(_) => {
            let mut remaining = amount;
            while let Some((&child, after)) = rest.split_first() {
                if remaining <= 0.0 {
                    break;
                }
                remaining -= match child {
                    Term::Account(idx) => { rest = after; leaf(idx as usize, remaining) }
                    _ => { let (inner, after) = children(rest); let taken = subtree(&rest[..inner.len() + 1], remaining, leaf); rest = after; taken }
                };
            }
            amount - remaining
        }
        Term::All(_) => {
            while let Some((&child, after)) = rest.split_first() {
                match child {
                    Term::Account(idx) => { rest = after; leaf(idx as usize, amount); }
                    _ => { let (inner, after) = children(rest); subtree(&rest[..inner.len() + 1], amount, leaf); rest = after; }
                }
            }
            amount
        }
        Term::Account(_) => {
            debug_assert!(false, "an account tree's root is an operator");
            0.0
        }
    }
}

/// Commit `amount` to the operator subtree `sub`, and whatever it could not cover to its first
/// account. Under `&` each side is committed in full the same way; a side that is one account
/// takes the whole amount.
fn commit_full(sub: &[Term], amount: f64, account_manager: &mut AccountManager, due_in: usize) {
    let taken = match sub[0] {
        Term::All(_) => {
            let (mut rest, _) = children(sub);
            while let Some((&child, after)) = rest.split_first() {
                match child {
                    Term::Account(idx) => { rest = after; account_manager.commit_account(idx as usize, amount, due_in); }
                    _ => { let (inner, after) = children(rest); commit_full(&rest[..inner.len() + 1], amount, account_manager, due_in); rest = after; }
                }
            }
            amount
        }
        _ => walk(sub, amount, &mut |idx, remaining| {
            let commit = remaining.min(account_manager.get_account_uncommitted_balance(idx));
            if commit > 0.0 {
                account_manager.commit_account(idx, commit, due_in);
            }
            commit
        }),
    };
    if taken < amount {
        account_manager.commit_account(first_account(sub), amount - taken, due_in);
    }
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
        match self {
            Combination::Account(idx) => Combination::Account(idx),
            Combination::Any(children) => {
                let mut flat = Vec::with_capacity(children.len());
                for child in children {
                    match child.canonical() {
                        Combination::Any(grandchildren) => flat.extend(grandchildren),
                        other => flat.push(other),
                    }
                }
                if flat.len() == 1 { flat.pop().unwrap() } else { Combination::Any(flat) }
            }
            Combination::All(children) => {
                let mut flat = Vec::with_capacity(children.len());
                for child in children {
                    match child.canonical() {
                        Combination::All(grandchildren) => flat.extend(grandchildren),
                        other => flat.push(other),
                    }
                }
                if flat.len() == 1 { flat.pop().unwrap() } else { Combination::All(flat) }
            }
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
            return Err(format!("account '{}' appears more than once; an account may appear once in a node's accounts", name));
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
                let idx = self.account_manager.get_account_idx(name).ok_or_else(|| {
                    if self.account_manager.get_group_idx(name).is_some() {
                        format!("'{}' is an account group; a node's 'accounts' takes account names \
                            (group targeting belongs in [ras.*] sections)", name)
                    } else {
                        format!("unknown account '{}'. Accounts are declared in [acc.*] sections.", name)
                    }
                })?;
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
