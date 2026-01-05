use crate::numerical::fifo_buffer::FifoBuffer;

#[test]
fn test_new_buffer_returns_zeros() {
    let mut buf = FifoBuffer::new(3);
    assert_eq!(buf.push(1.0), 0.0);
    assert_eq!(buf.push(2.0), 0.0);
    assert_eq!(buf.push(3.0), 0.0);
}

#[test]
fn test_fifo_order() {
    let mut buf = FifoBuffer::new(3);
    buf.push(1.0);
    buf.push(2.0);
    buf.push(3.0);
    assert_eq!(buf.push(4.0), 1.0);
    assert_eq!(buf.push(5.0), 2.0);
    assert_eq!(buf.push(6.0), 3.0);
}

#[test]
fn test_reset() {
    let mut buf = FifoBuffer::new(2);
    buf.push(10.0);
    buf.push(20.0);
    buf.reset();
    assert_eq!(buf.push(1.0), 0.0);
    assert_eq!(buf.push(2.0), 0.0);
}

#[test]
fn test_len() {
    let buf = FifoBuffer::new(5);
    assert_eq!(buf.len(), 5);
    assert!(!buf.is_empty());
}
