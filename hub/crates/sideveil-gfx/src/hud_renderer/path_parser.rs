//! Minimal compact SVG path `d` parser (`M/L/H/V/Q/C/Z` absolute & relative) for HUD paths.

use vello_cpu::kurbo::BezPath;

/// Parses a compact SVG path data string into a `kurbo::BezPath`, ignoring invalid tokens safely.
#[must_use]
pub(super) fn parse_compact_path(d: &str) -> BezPath {
    let mut path = BezPath::new();
    let tokens = tokenize(d);
    let mut i = 0;
    let mut cmd = b'\0';
    let mut cur = (0.0_f64, 0.0_f64);
    let mut start = (0.0_f64, 0.0_f64);

    while i < tokens.len() {
        if let Token::Cmd(c) = tokens[i] {
            cmd = c;
            i += 1;
            if cmd == b'Z' || cmd == b'z' {
                path.close_path();
                cur = start;
                continue;
            }
        }
        match cmd {
            b'M' | b'm' => {
                let Some((x, y)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                cur = if cmd == b'm' {
                    (cur.0 + x, cur.1 + y)
                } else {
                    (x, y)
                };
                start = cur;
                path.move_to(cur);
                cmd = if cmd == b'm' { b'l' } else { b'L' };
            }
            b'L' | b'l' => {
                let Some((x, y)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                cur = if cmd == b'l' {
                    (cur.0 + x, cur.1 + y)
                } else {
                    (x, y)
                };
                path.line_to(cur);
            }
            b'H' | b'h' => {
                let Some(x) = take_num(&tokens, &mut i) else {
                    break;
                };
                cur.0 = if cmd == b'h' { cur.0 + x } else { x };
                path.line_to(cur);
            }
            b'V' | b'v' => {
                let Some(y) = take_num(&tokens, &mut i) else {
                    break;
                };
                cur.1 = if cmd == b'v' { cur.1 + y } else { y };
                path.line_to(cur);
            }
            b'Q' | b'q' => {
                let Some((x1, y1)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                let Some((x2, y2)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                let (p1, p2) = if cmd == b'q' {
                    ((cur.0 + x1, cur.1 + y1), (cur.0 + x2, cur.1 + y2))
                } else {
                    ((x1, y1), (x2, y2))
                };
                path.quad_to(p1, p2);
                cur = p2;
            }
            b'C' | b'c' => {
                let Some((x1, y1)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                let Some((x2, y2)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                let Some((x3, y3)) = take_pair(&tokens, &mut i) else {
                    break;
                };
                let (p1, p2, p3) = if cmd == b'c' {
                    (
                        (cur.0 + x1, cur.1 + y1),
                        (cur.0 + x2, cur.1 + y2),
                        (cur.0 + x3, cur.1 + y3),
                    )
                } else {
                    ((x1, y1), (x2, y2), (x3, y3))
                };
                path.curve_to(p1, p2, p3);
                cur = p3;
            }
            _ => break,
        }
    }
    path
}

enum Token {
    Cmd(u8),
    Num(f64),
}

fn tokenize(input: &str) -> Vec<Token> {
    let bytes = input.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < bytes.len() && out.len() < 1024 {
        let b = bytes[i];
        if b.is_ascii_whitespace() || b == b',' {
            i += 1;
            continue;
        }
        if b.is_ascii_alphabetic() {
            out.push(Token::Cmd(b));
            i += 1;
            continue;
        }
        let start = i;
        if b == b'-' || b == b'+' {
            i += 1;
        }
        let mut seen_dot = false;
        while i < bytes.len() {
            let c = bytes[i];
            if c.is_ascii_digit() {
                i += 1;
            } else if c == b'.' && !seen_dot {
                seen_dot = true;
                i += 1;
            } else {
                break;
            }
        }
        if i > start {
            if let Ok(n) = input[start..i].parse::<f64>()
                && n.is_finite()
            {
                out.push(Token::Num(n));
            }
        } else {
            i += 1;
        }
    }
    out
}

fn take_num(tokens: &[Token], idx: &mut usize) -> Option<f64> {
    if let Some(Token::Num(v)) = tokens.get(*idx) {
        *idx += 1;
        Some(*v)
    } else {
        None
    }
}

fn take_pair(tokens: &[Token], idx: &mut usize) -> Option<(f64, f64)> {
    let x = take_num(tokens, idx)?;
    let y = take_num(tokens, idx)?;
    Some((x, y))
}
