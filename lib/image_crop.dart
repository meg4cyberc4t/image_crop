import 'dart:io';
import 'dart:ui';

import 'package:image_crop/src/image_options.dart';

import 'src/image_crop_platform_interface.dart';

export 'package:image_crop/src/image_options.dart';

export 'src/crop.dart';

abstract final class ImageCrop {
  static Future<bool?> requestPermissions() =>
      ImageCropPlatform.instance.requestPermissions();

  static Future<ImageOptions> getImageOptions({
    required File file,
  }) async =>
      ImageCropPlatform.instance.getImageOptions(file: file);

  static Future<File> cropImage({
    required File file,
    required Rect area,
    double? scale,
  }) =>
      ImageCropPlatform.instance.cropImage(
        file: file,
        area: area,
        scale: scale,
      );

  static Future<File> sampleImage({
    required File file,
    int? preferredSize,
    int? preferredWidth,
    int? preferredHeight,
  }) async =>
      ImageCropPlatform.instance.sampleImage(
        file: file,
        preferredHeight: preferredHeight,
        preferredSize: preferredSize,
        preferredWidth: preferredWidth,
      );
}
