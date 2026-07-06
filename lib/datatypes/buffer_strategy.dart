/// Buffer management strategy for memory optimization
enum BufferStrategy {
  /// Prioritize memory usage (smaller buffers, more frequent cleanup)
  memory,
  /// Balanced approach (default)
  balanced,
  /// Prioritize performance (larger buffers, less cleanup)
  performance,
}