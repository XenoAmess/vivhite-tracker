"""
生成Android应用图标脚本
创建开播(ON)和关播(OFF)两种状态的图标
"""
import os

# 创建mipmap目录
dirs = [
    'app/src/main/res/mipmap-mdpi',
    'app/src/main/res/mipmap-hdpi', 
    'app/src/main/res/mipmap-xhdpi',
    'app/src/main/res/mipmap-xxhdpi',
    'app/src/main/res/mipmap-xxxhdpi',
]

# 图标尺寸 (px)
sizes = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}

# 创建SVG图标内容
def create_on_svg(size):
    """开播状态图标 - 绿色圆形带播放符号"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 100 100">
  <defs>
    <linearGradient id="onGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4CAF50"/>
      <stop offset="100%" style="stop-color:#2E7D32"/>
    </linearGradient>
  </defs>
  <circle cx="50" cy="50" r="45" fill="url(#onGrad)"/>
  <circle cx="50" cy="50" r="40" fill="none" stroke="white" stroke-width="3"/>
  <path d="M 40 30 L 70 50 L 40 70 Z" fill="white"/>
  <circle cx="75" cy="25" r="12" fill="#FF5252"/>
  <text x="75" y="30" text-anchor="middle" fill="white" font-size="10" font-weight="bold">LIVE</text>
</svg>'''

def create_off_svg(size):
    """关播状态图标 - 灰色圆形带暂停符号"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 100 100">
  <defs>
    <linearGradient id="offGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#9E9E9E"/>
      <stop offset="100%" style="stop-color:#616161"/>
    </linearGradient>
  </defs>
  <circle cx="50" cy="50" r="45" fill="url(#offGrad)"/>
  <circle cx="50" cy="50" r="40" fill="none" stroke="white" stroke-width="3" stroke-dasharray="5,5"/>
  <rect x="35" y="30" width="10" height="40" rx="2" fill="white"/>
  <rect x="55" y="30" width="10" height="40" rx="2" fill="white"/>
  <text x="50" y="92" text-anchor="middle" fill="#616161" font-size="8">OFFLINE</text>
</svg>'''

def create_round_on_svg(size):
    """圆形开播图标"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 100 100">
  <defs>
    <linearGradient id="roundOnGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4CAF50"/>
      <stop offset="100%" style="stop-color:#2E7D32"/>
    </linearGradient>
  </defs>
  <circle cx="50" cy="50" r="50" fill="url(#roundOnGrad)"/>
  <path d="M 38 28 L 68 50 L 38 72 Z" fill="white"/>
</svg>'''

def create_round_off_svg(size):
    """圆形关播图标"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 100 100">
  <defs>
    <linearGradient id="roundOffGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#9E9E9E"/>
      <stop offset="100%" style="stop-color:#616161"/>
    </linearGradient>
  </defs>
  <circle cx="50" cy="50" r="50" fill="url(#roundOffGrad)"/>
  <rect x="33" y="28" width="12" height="44" rx="3" fill="white"/>
  <rect x="55" y="28" width="12" height="44" rx="3" fill="white"/>
</svg>'''

# 保存SVG文件
for name, size in sizes.items():
    dir_path = f'app/src/main/res/mipmap-{name}'
    os.makedirs(dir_path, exist_ok=True)
    
    # ON状态图标
    with open(f'{dir_path}/ic_launcher_on.svg', 'w') as f:
        f.write(create_on_svg(size))
    with open(f'{dir_path}/ic_launcher_on_round.svg', 'w') as f:
        f.write(create_round_on_svg(size))
    
    # OFF状态图标
    with open(f'{dir_path}/ic_launcher_off.svg', 'w') as f:
        f.write(create_off_svg(size))
    with open(f'{dir_path}/ic_launcher_off_round.svg', 'w') as f:
        f.write(create_round_off_svg(size))

print("SVG图标已生成!")
print("\n注意: Android需要PNG格式的图标。")
print("请使用Android Studio的Vector Asset工具将SVG转换为矢量图,")
print("或使用在线工具将SVG转换为PNG。")
print("\n同时，为简化部署，我将创建Android矢量图XML文件...")
