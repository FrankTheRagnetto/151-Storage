fn main() {
    let v: Vec<i32> = (1..=5).map(|x| x * x).collect();
    println!("{:?}", v);
}
