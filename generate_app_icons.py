#!/usr/bin/env python3
"""生成应用图标到各密度目录"""

try:
    from PIL import Image
except ImportError:
    print("需要安装PIL库: pip install pillow")
    exit(1)

import os

# 定义各密度尺寸
sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

base_path = r'F:\workspace\vivhite-tracker\app\src\main\res'

# 加载原图
on_img = Image.open(r'F:\workspace\vivhite-tracker\resources\on.png').convert('RGBA')
off_img = Image.open(r'F:\workspace\vivhite-tracker\resources\off.png').convert('RGBA')

for folder, size in sizes.items():
    dir_path = os.path.join(base_path, folder)
    os.makedirs(dir_path, exist_ok=True)
    
    # 生成 on 图标 - 缩放填满整个区域
    on_resized = on_img.resize((size, size), Image.LANCZOS)
    on_resized.save(os.path.join(dir_path, 'ic_launcher_on.png'), 'PNG')
    on_resized.save(os.path.join(dir_path, 'ic_launcher_on_round.png'), 'PNG')
    
    # 生成 off 图标 - 缩放填满整个区域
    off_resized = off_img.resize((size, size), Image.LANCZOS)
    off_resized.save(os.path.join(dir_path, 'ic_launcher_off.png'), 'PNG')
    off_resized.save(os.path.join(dir_path, 'ic_launcher_off_round.png'), 'PNG')
    
    print(f'生成 {folder}: {size}x{size}')

print('\n应用图标生成完成!')
