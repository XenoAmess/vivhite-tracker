Add-Type -AssemblyName System.Drawing

# 传统图标尺寸
$sizes = @{
    'mipmap-mdpi' = 48
    'mipmap-hdpi' = 72
    'mipmap-xhdpi' = 96
    'mipmap-xxhdpi' = 144
    'mipmap-xxxhdpi' = 192
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
    
    # 创建填充整个区域的图标（无安全边距）
    $bitmap = New-Object System.Drawing.Bitmap($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    
    # 拉伸填满整个画布（无留白）
    $graphics.DrawImage($onImg, 0, 0, $size, $size)
    $graphics.Dispose()
    
    $bitmap.Save("$dir\ic_launcher_on.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Save("$dir\ic_launcher_on_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    
    # 同样处理 off
    $bitmap2 = New-Object System.Drawing.Bitmap($size, $size)
    $graphics2 = [System.Drawing.Graphics]::FromImage($bitmap2)
    $graphics2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics2.DrawImage($offImg, 0, 0, $size, $size)
    $graphics2.Dispose()
    
    $bitmap2.Save("$dir\ic_launcher_off.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap2.Save("$dir\ic_launcher_off_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap2.Dispose()
    
    Write-Host "生成 $folder`: ${size}x${size}"
}

$onImg.Dispose()
$offImg.Dispose()

Write-Host "`n图标生成完成！"
