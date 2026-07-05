pub fn init_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_filter(android_logger::FilterBuilder::new().parse("off,dole=info").build())
                .with_tag("CORE")
                .format(|f, record| write!(f, "{}", record.args()))
        );
    }

    #[cfg(not(target_os = "android"))]
    {
        use std::io::Write;
        let _ = env_logger::builder()
            .filter_level(log::LevelFilter::Off)
            .filter_module("dole", log::LevelFilter::Info)
            .target(env_logger::Target::Stdout)
            .format(|buf, record| writeln!(buf, "[CORE] {} {}", record.level(), record.args()))
            .try_init();
    }
}
