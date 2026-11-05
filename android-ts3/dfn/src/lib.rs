use deep_filter_rt::tract::DfTract;
use jni::objects::{JClass, JShortArray};
use jni::sys::{jfloat, jlong};
use jni::JNIEnv;
use ndarray::Array2;

const HOP: usize = 480;

struct State {
    df: DfTract,
}

#[no_mangle]
pub extern "system" fn Java_com_webspeak_ts3_DeepFilterNet_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    match std::panic::catch_unwind(|| DfTract::default()) {
        Ok(df) => Box::into_raw(Box::new(State { df })) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_webspeak_ts3_DeepFilterNet_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut State));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_webspeak_ts3_DeepFilterNet_nativeSetAttenuation(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    db: jfloat,
) {
    if handle == 0 {
        return;
    }
    let state = unsafe { &mut *(handle as *mut State) };
    state.df.set_atten_lim(db);
}

#[no_mangle]
pub extern "system" fn Java_com_webspeak_ts3_DeepFilterNet_nativeSetPostFilter(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    beta: jfloat,
) {
    if handle == 0 {
        return;
    }
    let state = unsafe { &mut *(handle as *mut State) };
    state.df.set_pf_beta(beta.max(0.0));
}

#[no_mangle]
pub extern "system" fn Java_com_webspeak_ts3_DeepFilterNet_nativeProcess(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JShortArray,
    length: i32,
) -> jfloat {
    if handle == 0 || length <= 0 {
        return 0.0;
    }
    let state = unsafe { &mut *(handle as *mut State) };
    let mut pcm = vec![0i16; length as usize];
    if env
        .get_short_array_region(&samples, 0, &mut pcm)
        .is_err()
    {
        return 0.0;
    }

    let mut noisy = Array2::<f32>::zeros((1, HOP));
    let mut enhanced = Array2::<f32>::zeros((1, HOP));
    let mut offset = 0usize;
    let mut last_lsnr = 0.0f32;
    while offset + HOP <= pcm.len() {
        for i in 0..HOP {
            noisy[[0, i]] = pcm[offset + i] as f32 / 32768.0;
        }
        match state.df.process(noisy.view(), enhanced.view_mut()) {
            Ok(lsnr) => last_lsnr = lsnr,
            Err(_) => {
                enhanced.fill(0.0);
            }
        }
        for i in 0..HOP {
            let value = (enhanced[[0, i]] * 32768.0).clamp(-32768.0, 32767.0);
            pcm[offset + i] = value as i16;
        }
        offset += HOP;
    }
    // A trailing partial hop is passed through unchanged rather than clicked.
    if offset < pcm.len() {
        // get_short_array_region already copied the original tail into pcm.
    }
    let _ = env.set_short_array_region(&samples, 0, &pcm);
    last_lsnr
}
