Add-Type -AssemblyName System.Drawing

# 自适应图标前景尺寸（108dp基准）
$sizes = @{
    'mipmap-mdpi' = 108
    'mipmap-hdpi' = 162
    'mipmap-xhdpi' = 216
    'mipmap-xxhdpi' = 324
    'mipmap-xxxhdpi' = 432
}

# 加载原图
$onImg = [System.Drawing.Image]::FromFile('F:\workspace\vivhite-tracker\resources\on.png')
$offImg = [System.Drawing.Image]::FromFile('F:\workspace\vivhite-tracker\resources\off.png')

foreach ($folder in $sizes.Keys) {
    $size = $sizes[$folder]
    $dir = "F:\workspace\vivhite-tracker\app\src\main\res\$folder"
    
    if (!(Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    
    # 生成 on 前景（填满108dp画布）
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($onImg, 0, 0, $size, $size)
    $graphics.Dispose()
    $bitmap.Save("$dir\ic_launcher_on_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    
    # 生成 off 前景
    $bitmap2 = New-Object System.Drawing.Bitmap($size, $size)
    $graphics2 = [System.Drawing.Graphics]::FromImage($bitmap2)
    $graphics2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics2.DrawImage($offImg, 0, 0, $size, $size)
    $graphics2.Dispose()
    $bitmap2.Save("$dir\ic_launcher_off_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap2.Dispose()
    
    Write-Host "生成 $folder 前景: ${size}x${size}"
}

$onImg.Dispose()
$offImg.Dispose()

Write-Host "Done!"
