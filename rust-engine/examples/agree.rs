//! Gate E=0 (G2a): roda o motor Rust (`fd_search`) sobre as 54.100 queries do
//! contest e compara o fraudCount com o ground truth (`expected_fraud_score*5`).
//!
//! Lê `agree-queries.bin` (gerado por `DumpQueries.java`): o parser Java produz
//! os queryVector[14] (caminho de produção) e o expected vem do test-data.json.
//! Formato: [u32 count] então por entrada [u8 parseOk][14 f32 LE][u8 expectedFC].
//!
//!   cargo run --release --example agree           (paths default)
//!   cargo run --release --example agree -- <kdt> <bin>

use std::io::Read;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let kdt = args.get(1).cloned().unwrap_or_else(|| {
        concat!(env!("CARGO_MANIFEST_DIR"), "/../api/src/main/resources/references.kdt").to_string()
    });
    let bin = args.get(2).cloned().unwrap_or_else(|| {
        concat!(env!("CARGO_MANIFEST_DIR"), "/../agree-queries.bin").to_string()
    });
    eprintln!("agree: kdt={}\nagree: bin={}", kdt, bin);

    if let Err(e) = fdsearch::init_from_path(&kdt, false) {
        eprintln!("init falhou: {}", e);
        std::process::exit(2);
    }

    let mut f = match std::fs::File::open(&bin) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("abrir {}: {} (rode DumpQueries.java antes)", bin, e);
            std::process::exit(2);
        }
    };
    let mut buf = Vec::new();
    if let Err(e) = f.read_to_end(&mut buf) {
        eprintln!("ler {}: {}", bin, e);
        std::process::exit(2);
    }
    if buf.len() < 4 {
        eprintln!("agree-queries.bin curto demais");
        std::process::exit(2);
    }

    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    let mut off = 4usize;
    let mut q = [0f32; 14];

    let mut checked = 0usize;
    let mut mismatches = 0usize;
    let mut fp = 0usize; // rust aprovou, esperado fraude
    let mut fn_ = 0usize; // rust fraude, esperado aprovado
    let mut score_mis = 0usize;

    for _ in 0..count {
        if off + 1 + 14 * 4 + 1 > buf.len() {
            eprintln!("truncado na entrada {}", checked);
            std::process::exit(2);
        }
        let parse_ok = buf[off];
        off += 1;
        for i in 0..14 {
            q[i] = f32::from_le_bytes([buf[off], buf[off + 1], buf[off + 2], buf[off + 3]]);
            off += 4;
        }
        let expected_fc = buf[off] as i32;
        off += 1;

        let rust_fc = if parse_ok != 0 {
            fdsearch::search_query(&q)
        } else {
            0 // fail-open (== FraudController)
        };
        checked += 1;

        if rust_fc != expected_fc {
            mismatches += 1;
            score_mis += 1;
            let r_ap = rust_fc < 3;
            let e_ap = expected_fc < 3;
            if r_ap && !e_ap {
                fp += 1;
            } else if !r_ap && e_ap {
                fn_ += 1;
            }
            if mismatches <= 20 {
                eprintln!(
                    "mismatch #{}: rust_fc={} expected_fc={} q={:?}",
                    checked, rust_fc, expected_fc, q
                );
            }
        }
    }

    println!("──────────────────────────────────────────────");
    println!("entries checked : {}", checked);
    println!(
        "mismatches      : {}  (E approved FP={} FN={}, fraud_score_mis={})",
        mismatches, fp, fn_, score_mis
    );
    println!(
        "RESULT          : {}",
        if mismatches == 0 {
            "PASS — E=0 (rust == ground truth nas 54.100)"
        } else {
            "FAIL"
        }
    );
    println!("──────────────────────────────────────────────");
    std::process::exit(if mismatches == 0 { 0 } else { 1 });
}
