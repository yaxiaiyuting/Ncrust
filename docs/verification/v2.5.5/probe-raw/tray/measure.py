#!/usr/bin/env python3
"""托盘几何测量：逐行像素剖面。托盘背景是纯 surface 色 (26,26,26)，文本行会把它抬起来。
用法: python3 measure.py <screenshot.png> [x0] [x1] [y0] [y1]"""
import sys
import numpy as np
from PIL import Image
p = sys.argv[1]
x0 = int(sys.argv[2]) if len(sys.argv) > 2 else 210
x1 = int(sys.argv[3]) if len(sys.argv) > 3 else 1000
y0 = int(sys.argv[4]) if len(sys.argv) > 4 else 0
y1 = int(sys.argv[5]) if len(sys.argv) > 5 else 10**9
a = np.array(Image.open(p).convert('RGB'))
h = a.shape[0]
print(f"# {p} size={Image.open(p).size}")
print("# y mean std max")
for y in range(y0, min(y1, h)):
    r = a[y, x0:x1]
    print(y, round(r.mean(), 1), round(r.std(), 1), int(r.max()))
