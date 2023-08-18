import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:image_crop/src/image_options.dart';

import 'image_crop_platform_interface.dart';

/// An implementation of [ImageCropPlatform] that uses method channels.
class MethodChannelImageCrop extends ImageCropPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('plugins.lykhonis.com/image_crop');

  @override
  Future<bool?> requestPermissions() =>
      methodChannel.invokeMethod('requestPermissions');

  @override
  Future<ImageOptions> getImageOptions({
    required File file,
  }) async {
    final result = await methodChannel.invokeMethod(
      'getImageOptions',
      {'path': file.path},
    );
    return ImageOptions(
      width: result['width'],
      height: result['height'],
    );
  }

  @override
  Future<File> cropImage({
    required File file,
    required Rect area,
    double? scale,
  }) async {
    final String? path = await methodChannel.invokeMethod<String>('cropImage', {
      'path': file.path,
      'left': area.left,
      'top': area.top,
      'right': area.right,
      'bottom': area.bottom,
      'scale': scale ?? 1.0,
    });
    return File(path!);
  }

  @override
  Future<File> sampleImage({
    required File file,
    int? preferredSize,
    int? preferredWidth,
    int? preferredHeight,
  }) async {
    assert(() {
      if (preferredSize == null &&
          (preferredWidth == null || preferredHeight == null)) {
        throw ArgumentError(
          'Preferred size or both width and height '
          'of a resampled image must be specified.',
        );
      }
      return true;
    }());

    final String? path =
        await methodChannel.invokeMethod<String>('sampleImage', {
      'path': file.path,
      'maximumWidth': preferredSize ?? preferredWidth,
      'maximumHeight': preferredSize ?? preferredHeight,
    });

    return File(path!);
  }
}
