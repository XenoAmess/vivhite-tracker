#!/usr/bin/env python3
"""
生成Android应用图标
需要安装PIL: pip install pillow
"""

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("需要安装PIL库: pip install pillow")
    exit(1)

import os

def create_on_icon(size):
    """创建开播图标 - 绿色带播放按钮"""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    margin = size // 10
    circle_size = size - 2 * margin
    
    # 外圈 - 亮绿色
    draw.ellipse([margin, margin, size - margin, size - margin], 
                 fill=(76, 175, 80, 255), outline=(46, 125, 50, 255), width=size//20)
    
    # 内圈
    inner_margin = margin + size // 8
    draw.ellipse([inner_margin, inner_margin, size - inner_margin, size - inner_margin],
                 fill=(46, 125, 50, 255))
    
    # 播放三角形
    triangle_size = size // 3
    center_x, center_y = size // 2, size // 2
    points = [
        (center_x - triangle_size//3, center_y - triangle_size//2),
        (center_x - triangle_size//3, center_y + triangle_size//2),
        (center_x + triangle_size//2, center_y)
    ]
    draw.polygon(points, fill=(255, 255, 255, 255))
    
    # LIVE红点
    dot_size = size // 5
    dot_x = size - margin - dot_size
    dot_y = margin + dot_size//2
    draw.ellipse([dot_x - dot_size//2, dot_y - dot_size//2, 
                  dot_x + dot_size//2, dot_y + dot_size//2], 
                 fill=(255, 82, 82, 255))
    
    return img

def create_off_icon(size):
    """创建关播图标 - 灰色带暂停按钮"""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    margin = size // 10
    
    # 外圈 - 灰色
    draw.ellipse([margin, margin, size - margin, size - margin],
                 fill=(158, 158, 158, 255), outline=(97, 97, 97, 255), width=size//20)
    
    # 内圈
    inner_margin = margin + size // 8
    draw.ellipse([inner_margin, inner_margin, size - inner_margin, size - inner_margin],
                 fill=(97, 97, 97, 255))
    
    # 暂停符号 - 两个竖条
    bar_width = size // 10
    bar_height = size // 3
    spacing = size // 8
    center_x, center_y = size // 2, size // 2
    
    # 左条
    draw.rounded_rectangle(
        [center_x - spacing - bar_width, center_y - bar_height//2,
         center_x - spacing, center_y + bar_height//2],
        radius=bar_width//4, fill=(255, 255, 255, 255))
    
    # 右条
    draw.rounded_rectangle(
        [center_x + spacing, center_y - bar_height//2,
         center_x + spacing + bar_width, center_y + bar_height//2],
        radius=bar_width//4, fill=(255, 255, 255, 255))
    
    return img

def create_round_icon(source_img):
    """创建圆形图标（不带背景）"""
    size = source_img.size[0]
    # 已经是圆形的，直接返回
    return source_img

# 尺寸定义
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

# 生成图标
for name, size in sizes.items():
    dir_path = f'app/src/main/res/mipmap-{name}'
    os.makedirs(dir_path, exist_ok=True)
    
    # 生成开播图标
    on_icon = create_on_icon(size)
    on_icon.save(f'{dir_path}/ic_launcher_on.png', 'PNG')
    on_icon.save(f'{dir_path}/ic_launcher_on_round.png', 'PNG')
    
    # 生成关播图标
    off_icon = create_off_icon(size)
    off_icon.save(f'{dir_path}/ic_launcher_off.png', 'PNG')
    off_icon.save(f'{dir_path}/ic_launcher_off_round.png', 'PNG')
    
    print(f"生成 {name} 尺寸图标: {size}x{size}")

print("\n图标生成完成!")
