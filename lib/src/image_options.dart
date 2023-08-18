final class ImageOptions {
  final int width;
  final int height;

  const ImageOptions({
    required this.width,
    required this.height,
  });

  @override
  int get hashCode => Object.hash(width, height);

  @override
  bool operator ==(other) =>
      other is ImageOptions && other.width == width && other.height == height;

  @override
  String toString() => '$runtimeType(width: $width, height: $height)';
}
