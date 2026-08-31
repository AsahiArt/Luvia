fn main() {
    if let Err(error) = luvia_host::run() {
        eprintln!("luvia-host: {error}");
        std::process::exit(1);
    }
}
