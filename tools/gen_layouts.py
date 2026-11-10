#!/usr/bin/env python3
"""
Generates every widget layout (app/src/main/res/layout/widget_*.xml) from shared pieces, so all
sizes stay consistent. Both widgets (Audiobooks, Music) use these same layouts; the renderer fills
only the views that apply. Edit here, then run:  python3 tools/gen_layouts.py

View ids the renderer relies on:
  art, play, content, badge, title, claude, update, line2, line3  (all text layouts)
  time, time_static                                                (strip "time left"; elapsed in time_row)
  up_next, time_row, progress, duration                            (big sizes)
  prev, back, fwd, next, speed, bookmark, action1..4               (controls)
  grid + rec1..4 / rec1_art..                                       (3x1 grid)
  rec1..3 / recN_art                                                (4x1 thumbs)
  recent_label, recN, recN_art, recN_icon, recN_title, recN_sub    (recent rows)
  bm_label, bmN, bmN_text, bmN_when                                (bookmarks, XL)
  switch1, switch2                                                 (source switcher, XL)
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'res', 'layout')
H = '<?xml version="1.0" encoding="utf-8"?>\n'
NS = ' xmlns:android="http://schemas.android.com/apk/res/android"'


def ind(s, n):
    pad = ' ' * n
    return '\n'.join((pad + l if l.strip() else l) for l in s.strip('\n').split('\n')) + '\n'


def root(tag, body, comment, orient=None, pad="8dp", extra=""):
    o = f'\n    android:orientation="{orient}"' if orient else ''
    return (H + f'<!-- {comment} -->\n<{tag}{NS}\n    android:id="@+id/root"\n    android:layout_width="match_parent"\n'
            f'    android:layout_height="match_parent"\n    android:background="@drawable/widget_bg"{o}\n'
            f'    android:padding="{pad}"{extra}>\n\n' + ind(body, 4) + f'</{tag}>\n')


def cover_overlay(width='wrap_content', height='match_parent', gravity=None, weight=None, update=False):
    g = f'\n    android:layout_gravity="{gravity}"' if gravity else ''
    w = f'\n    android:layout_weight="{weight}"' if weight else ''
    u = '''
    <ImageView
        android:id="@+id/update"
        style="@style/UpdateSymbol"
        android:layout_gravity="top|end"
        android:layout_margin="4dp" />''' if update else ''
    return f'''<FrameLayout
    android:layout_width="{width}"
    android:layout_height="{height}"{g}{w}>

    <ImageView
        android:id="@+id/art"
        style="@style/Cover" />

    <ImageView
        android:id="@+id/play"
        style="@style/PlayOverlay" />{u}
</FrameLayout>
'''


def cover_plain(width='wrap_content', height='match_parent', gravity=None, weight=None):
    g = f'\n    android:layout_gravity="{gravity}"' if gravity else ''
    w = f'\n    android:layout_weight="{weight}"' if weight else ''
    return f'''<ImageView
    android:id="@+id/art"
    style="@style/Cover"
    android:layout_width="{width}"
    android:layout_height="{height}"{g}{w} />
'''


def text_col(title_lines=1, line2_lines=1, time=False, width='0dp', height='match_parent', weight='1',
             margin='10dp', gravity='center_vertical'):
    """[app icon] title [update] / line2 (chapter | artist) / time left / line3 (author | album)."""
    t = '''
<Chronometer
    android:id="@+id/time"
    style="@style/TimeLeft" />

<TextView
    android:id="@+id/time_static"
    style="@style/TimeLeftStatic" />''' if time else ''
    hw = f'\n    android:layout_weight="{weight}"' if weight else ''
    ms = f'\n    android:layout_marginStart="{margin}"' if margin else ''
    return f'''<LinearLayout
    android:id="@+id/content"
    android:layout_width="{width}"
    android:layout_height="{height}"{ms}{hw}
    android:gravity="{gravity}"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <ImageView
            android:id="@+id/badge"
            style="@style/Badge" />

        <TextView
            android:id="@+id/title"
            style="@style/Title"
            android:maxLines="{title_lines}" />

        <ImageView
            android:id="@+id/claude"
            style="@style/ClaudeLink" />

        <ImageView
            android:id="@+id/update"
            style="@style/UpdateSymbol" />
    </LinearLayout>

    <TextView
        android:id="@+id/line2"
        style="@style/Chapter"
        android:maxLines="{line2_lines}" />
{ind(t, 4) if time else ''}
    <TextView
        android:id="@+id/line3"
        style="@style/Author" />
</LinearLayout>
'''


PLAYER_INFO = '''<TextView
    android:id="@+id/up_next"
    style="@style/UpNext" />

<LinearLayout
    android:id="@+id/time_row"
    style="@style/TimeRow">

    <Chronometer
        android:id="@+id/time"
        style="@style/TimeText" />

    <TextView
        android:id="@+id/time_static"
        style="@style/TimeText" />

    <ProgressBar
        android:id="@+id/progress"
        style="@style/ProgressLine" />

    <TextView
        android:id="@+id/duration"
        style="@style/TimeText" />
</LinearLayout>
'''


def controls(slots):
    """Every control either widget may use; the renderer shows the ones that apply."""
    items = [
        ('prev', 'Ctl', 'Previous', 'ic_prev'),
        ('back', 'Ctl', 'Skip back', 'ic_replay'),
        ('play', None, None, None),
        ('fwd', 'Ctl', 'Skip forward', 'ic_forward'),
        ('next', 'Ctl', 'Next', 'ic_next'),
    ]
    parts = []
    for vid, style, desc, src in items:
        if vid == 'play':
            parts.append('<ImageView\n    android:id="@+id/play"\n    style="@style/PlayBig" />')
        else:
            parts.append(f'<ImageView\n    android:id="@+id/{vid}"\n    style="@style/{style}"\n'
                         f'    android:contentDescription="{desc}"\n    android:src="@drawable/{src}" />')
    for k in range(1, slots + 1):
        parts.append(f'<ImageView\n    android:id="@+id/action{k}"\n    style="@style/ActionSlot" />')
    parts.append('<ImageView\n    android:id="@+id/bookmark"\n    style="@style/Ctl"\n'
                 '    android:contentDescription="Bookmark"\n    android:src="@drawable/ic_bookmark" />')
    parts.append('<TextView\n    android:id="@+id/speed"\n    style="@style/SpeedChip" />')
    return f'''<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="4dp"
    android:gravity="center"
    android:orientation="horizontal">

{ind(chr(10).join(parts), 4)}</LinearLayout>
'''


def grid_cell(k, first):
    m = '' if first else '\n    android:layout_marginStart="3dp"'
    return f'''<FrameLayout
    android:id="@+id/rec{k}"
    style="@style/GridCell"{m}>

    <ImageView
        android:id="@+id/rec{k}_art"
        style="@style/RecentThumb" />
</FrameLayout>'''


GRID = '''<!-- 2x2 grid of other recent items (books | albums and mixes). No repeats. -->
<LinearLayout
    android:id="@+id/grid"
    android:layout_width="wrap_content"
    android:layout_height="match_parent"
    android:layout_marginStart="8dp"
    android:orientation="vertical"
    android:visibility="gone">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="horizontal">

''' + ind(grid_cell(1, True), 8) + '\n' + ind(grid_cell(2, False), 8) + '''    </LinearLayout>

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="0dp"
        android:layout_marginTop="3dp"
        android:layout_weight="1"
        android:orientation="horizontal">

''' + ind(grid_cell(3, True), 8) + '\n' + ind(grid_cell(4, False), 8) + '''    </LinearLayout>
</LinearLayout>
'''


def thumbs(n):
    items = []
    for k in range(1, n + 1):
        items.append(f'''<FrameLayout
    android:id="@+id/rec{k}"
    android:layout_width="wrap_content"
    android:layout_height="match_parent"
    android:layout_marginStart="6dp"
    android:visibility="gone">

    <ImageView
        android:id="@+id/rec{k}_art"
        style="@style/RecentThumb" />
</FrameLayout>''')
    return '\n'.join(items) + '\n'


def rows(n):
    items = []
    for k in range(1, n + 1):
        items.append(f'''<LinearLayout
    android:id="@+id/rec{k}"
    style="@style/RecentRow">

    <ImageView
        android:id="@+id/rec{k}_art"
        style="@style/RecentRowArt" />

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:layout_weight="1"
        android:orientation="vertical">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <ImageView
                android:id="@+id/rec{k}_icon"
                style="@style/RecentIcon" />

            <TextView
                android:id="@+id/rec{k}_title"
                style="@style/RecentTitle" />
        </LinearLayout>

        <TextView
            android:id="@+id/rec{k}_sub"
            style="@style/RecentSub" />
    </LinearLayout>
</LinearLayout>''')
    return '\n'.join(items) + '\n'


def bookmarks(n):
    items = []
    for k in range(1, n + 1):
        items.append(f'''<LinearLayout
    android:id="@+id/bm{k}"
    style="@style/BookmarkRow">

    <ImageView
        android:layout_width="14dp"
        android:layout_height="14dp"
        android:layout_marginEnd="6dp"
        android:src="@drawable/ic_bookmark" />

    <TextView
        android:id="@+id/bm{k}_text"
        style="@style/RecentTitle"
        android:textSize="12sp" />

    <TextView
        android:id="@+id/bm{k}_when"
        style="@style/RecentSub"
        android:layout_width="wrap_content"
        android:layout_marginStart="8dp" />
</LinearLayout>''')
    return '''<TextView
    android:id="@+id/bm_label"
    style="@style/SectionLabel"
    android:text="Bookmarks" />

''' + '\n\n'.join(items) + '\n'


RECENT_LABEL = '''<TextView
    android:id="@+id/recent_label"
    style="@style/SectionLabel"
    android:text="Recent" />
'''

STRIP_ATTRS = '\n    android:gravity="center_vertical"'

L = {}

L['widget_tile'] = root('FrameLayout', cover_overlay('wrap_content', 'match_parent', gravity='center', update=True),
    '1x1: art as big as it gets, with a big play/pause filling it.', pad='4dp')

L['widget_strip_s'] = root('LinearLayout', cover_overlay() + '\n' + text_col(margin='8dp', time=True),
    '2x1: art filled by a big play/pause, then [app icon] title / line 2 / time left / line 3. Tap text to expand.',
    orient='horizontal', pad='5dp', extra=STRIP_ATTRS)

L['widget_strip'] = root('LinearLayout', cover_overlay() + '\n' + text_col(time=True) + '\n' + GRID,
    '3x1 in thirds: art filled by a big play/pause | text (tap to expand) | 2x2 grid of other recent items.',
    orient='horizontal', pad='5dp', extra=STRIP_ATTRS)

L['widget_strip_l'] = root('LinearLayout', cover_overlay() + '\n' + text_col(time=True) + '''
<ImageView
    android:id="@+id/bookmark"
    style="@style/Ctl"
    android:layout_width="36dp"
    android:layout_weight="0"
    android:contentDescription="Bookmark"
    android:src="@drawable/ic_bookmark" />

<ImageView
    android:id="@+id/action1"
    style="@style/ActionSlot"
    android:layout_width="36dp"
    android:layout_weight="0" />

<ImageView
    android:id="@+id/next"
    style="@style/Ctl"
    android:layout_width="36dp"
    android:layout_weight="0"
    android:contentDescription="Next"
    android:src="@drawable/ic_next" />
''' + thumbs(3),
    '4x1: art with play/pause, text, a quick action and next, then other recent items.',
    orient='horizontal', pad='5dp', extra=STRIP_ATTRS)

L['widget_square'] = root('LinearLayout',
    cover_overlay('wrap_content', '0dp', gravity='center_horizontal', weight='1') + '\n' +
    text_col(width='match_parent', height='wrap_content', weight=None, margin=None),
    '2x2: big art filled by a big play/pause, then the text (tap to expand).', orient='vertical', pad='8dp')

L['widget_large'] = root('LinearLayout',
    '''<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="0dp"
    android:layout_weight="1"
    android:orientation="horizontal">

''' + ind(cover_plain() + '\n' + text_col(title_lines=2, line2_lines=2), 4) + '''</LinearLayout>

''' + PLAYER_INFO + '\n' + controls(3),
    '3x2 / 4x2: big art, text, up next, progress, every control.', orient='vertical', pad='8dp')

L['widget_tall'] = root('LinearLayout',
    cover_plain('wrap_content', '0dp', gravity='center_horizontal', weight='1') + '\n' +
    text_col(title_lines=2, line2_lines=2, width='match_parent', height='wrap_content', weight=None, margin=None) + '\n' +
    PLAYER_INFO + '\n' + controls(1) + '\n' + RECENT_LABEL + '\n' + rows(3),
    '2x3+: art, text, progress, controls, recent items.', orient='vertical', pad='8dp')

L['widget_xl'] = root('LinearLayout',
    '''<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal">

''' + ind(cover_plain('140dp', '140dp') + '\n' + text_col(title_lines=3, line2_lines=2, height='140dp', gravity='top'), 4) + '''</LinearLayout>

''' + PLAYER_INFO + '\n' + controls(4) + '\n' + bookmarks(3) + '''
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="6dp"
    android:gravity="center_vertical"
    android:orientation="horizontal">

    <TextView
        android:id="@+id/recent_label"
        style="@style/SectionLabel"
        android:layout_width="0dp"
        android:layout_weight="1"
        android:text="Recent" />

    <ImageView
        android:id="@+id/switch1"
        style="@style/SwitchIcon" />

    <ImageView
        android:id="@+id/switch2"
        style="@style/SwitchIcon"
        android:layout_marginStart="4dp" />
</LinearLayout>

''' + rows(4),
    '4x3+ and the expand panel: everything.', orient='vertical', pad='12dp')

for name, xml in L.items():
    with open(os.path.join(OUT, name + '.xml'), 'w') as f:
        f.write(xml)
print('wrote', len(L), 'layouts')
