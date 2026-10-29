// SPDX-License-Identifier: AGPL-3.0-or-later
// JNI adapter; caj2pdf-core 0.8.1 is vendored under its original MIT license.

use caj2pdf_core::{
    ConversionOptions, Fonts, Limits, Progress, RangedSource, convert, native::SeekableSource,
    native_font_mode,
};
use caj2pdf_core::hnc8::{C8FontSource, C8PageFonts};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use std::{fs::File, io::{BufWriter, Write}, panic::{catch_unwind, AssertUnwindSafe}, ptr, time::{Duration, Instant}};

struct BoundedProgress { started: Instant }
impl Progress for BoundedProgress {
    fn is_cancelled(&self) -> bool { self.started.elapsed() > Duration::from_secs(180) }
}

fn convert_file(source: &str, destination: &str, cjk_path: &str, latin_path: &str) -> Result<(), String> {
    let mut source = SeekableSource::new(File::open(source).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let limits = Limits {
        max_input_bytes: 4 * 1024 * 1024 * 1024,
        max_output_bytes: 2 * 1024 * 1024 * 1024,
        max_allocation_bytes: 64 * 1024 * 1024,
        ..Limits::default()
    };
    let mut progress = BoundedProgress { started: Instant::now() };
    let mode = native_font_mode(&mut source, &limits, &mut progress).map_err(|e| e.to_string())?;
    let mut options = ConversionOptions::default();
    // The core refuses unrepresentable glyphs; no page or text is silently omitted.
    if mode.is_some() {
        let mut sources: Vec<C8FontSource<Box<dyn RangedSource>>> = Vec::new();
        for path in [cjk_path, latin_path] {
            if path.is_empty() { return Err("Este CAJ de texto requiere fuentes CJK y latina disponibles en Android".into()); }
            let font = SeekableSource::new(File::open(path).map_err(|e| format!("Fuente CAJ: {e}"))?).map_err(|e| e.to_string())?;
            sources.push(C8FontSource { source: Box::new(font), face: 0 });
        }
        options.fonts = Some(Fonts {
            sources,
            roles: Some(C8PageFonts {
                cjk: 0, latin: 1, alternate_latin: None, decoration: None, symbols: None,
                latin_state3: None, latin_state28: None, latin_state31: None,
                cjk_sans: None, cjk_kai: None, cjk_system: None,
            }),
            ..Fonts::default()
        });
    }
    let file = File::create(destination).map_err(|e| e.to_string())?;
    let mut output = BufWriter::new(file);
    let report = convert(&mut source, &mut output, options, &limits, &mut progress).map_err(|e| e.to_string())?;
    if report.pages_converted == 0 || !report.omitted_pages.is_empty() {
        return Err("La conversión CAJ no ha conservado todas las páginas".into());
    }
    output.flush().map_err(|e| e.to_string())?;
    output.get_ref().sync_all().map_err(|e| e.to_string())?;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_valerogarte_comicsreader_nativeapp_reader_CajNative_convert(
    mut env: JNIEnv, _class: JClass, source: JString, destination: JString, cjk: JString, latin: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        let source: String = env.get_string(&source).map_err(|e| e.to_string())?.into();
        let destination: String = env.get_string(&destination).map_err(|e| e.to_string())?.into();
        let cjk: String = env.get_string(&cjk).map_err(|e| e.to_string())?.into();
        let latin: String = env.get_string(&latin).map_err(|e| e.to_string())?.into();
        convert_file(&source, &destination, &cjk, &latin)
    }));
    let error = match result { Ok(Ok(())) => return ptr::null_mut(), Ok(Err(error)) => error, Err(_) => "No se ha podido decodificar este archivo CAJ".into() };
    env.new_string(error).map(|value| value.into_raw()).unwrap_or(ptr::null_mut())
}
