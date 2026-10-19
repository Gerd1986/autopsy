"""Python-Port von mergeFiles 0.3.3 (Original: Jens Elstner).

Start ohne Argumente: grafische Oberfläche. --help: Kommandozeile.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
import os
from pathlib import Path
import queue
import sys
import tempfile
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk


class Cancelled(Exception):
    pass


def parse_order(value, count):
    if not value.strip():
        return list(range(count))
    try:
        order = [int(item.strip()) - 1 for item in value.split(',')]
    except ValueError:
        raise ValueError('Reihenfolge als Zahlenliste eingeben, z. B. 3,3,1,2,2.') from None
    if any(item < 0 or item >= count for item in order):
        raise ValueError(f'Die Dateinummern müssen zwischen 1 und {count} liegen.')
    return order


def merge_files(files, destination, block_size=1, order='', reverse=False,
                progress=None, cancel=None):
    """Nur vollständige gemeinsame Blöcke; auch ausgelassene Dateien begrenzen die Länge.

    Erst nach erfolgreicher Verarbeitung ersetzt die temporäre Datei das Ziel.
    """
    paths = [Path(p).resolve() for p in files]
    target = Path(destination).resolve()
    if not paths:
        raise ValueError('Bitte mindestens eine Eingabedatei auswählen.')
    if not isinstance(block_size, int) or block_size < 1:
        raise ValueError('Die Blockgröße muss eine positive ganze Zahl sein.')
    indices = parse_order(order, len(paths))
    for path in paths:
        if path == target or (target.exists() and os.path.samefile(path, target)):
            raise ValueError('Die Ausgabedatei darf keine Eingabedatei sein.')
    temporary = None
    try:
        with ExitStack() as stack:
            handles = [stack.enter_context(path.open('rb')) for path in paths]
            rounds = min(os.fstat(f.fileno()).st_size for f in handles) // block_size
            total = rounds * block_size * len(indices)
            out = stack.enter_context(tempfile.NamedTemporaryFile(
                mode='wb', prefix='.merge-', dir=target.parent, delete=False))
            temporary = Path(out.name)
            written = 0
            if progress:
                progress(0, total)
            # Batch reads keep byte-wise merges efficient without loading entire files.
            batch_rounds = max(1, (4 * 1024 * 1024) //
                               (block_size * (len(paths) + len(indices))))
            remaining = rounds
            while remaining:
                if cancel and cancel.is_set():
                    raise Cancelled()
                n = min(batch_rounds, remaining)
                size = n * block_size
                chunks = [f.read(size) for f in handles]
                if any(len(chunk) != size for chunk in chunks):
                    raise OSError('Eine Eingabedatei wurde während der Verarbeitung verkürzt.')
                merged = bytearray()
                for offset in range(0, size, block_size):
                    for index in indices:
                        block = chunks[index][offset:offset + block_size]
                        merged.extend(block[::-1] if reverse else block)
                out.write(merged)
                written += len(merged)
                remaining -= n
                if progress:
                    progress(written, total)
            if cancel and cancel.is_set():
                raise Cancelled()
        os.replace(temporary, target)
        temporary = None
        return written
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class App(ttk.Frame):
    def __init__(self, root):
        super().__init__(root, padding=16)
        self.pack(fill='both', expand=True)
        self.root = root
        self.files = []
        self.events = queue.Queue()
        self.cancel = threading.Event()
        self.running = False
        root.title('mergeFiles – Dateien verschachteln')
        root.geometry('1120x850')
        root.minsize(950, 750)
        self.columnconfigure(0, weight=1)
        self.rowconfigure(5, weight=1)
        ttk.Label(self, text='Eingabedateien in gewünschter Reihenfolge',
                  font=('Segoe UI', 12, 'bold')).grid(sticky='w')
        self.listbox = tk.Listbox(self, selectmode='extended', height=4)
        self.listbox.grid(row=1, sticky='nsew', pady=8)
        buttons = ttk.Frame(self)
        buttons.grid(row=2, sticky='w')
        self.controls = []
        for text, command in [('Hinzufügen …', self.add), ('Entfernen', self.remove),
                              ('Nach oben', lambda: self.move(-1)),
                              ('Nach unten', lambda: self.move(1))]:
            button = ttk.Button(buttons, text=text, command=command)
            button.pack(side='left', padx=(0, 6))
            self.controls.append(button)
        options = ttk.Frame(self)
        options.grid(row=3, sticky='ew', pady=14)
        options.columnconfigure(1, weight=1)
        self.block = tk.StringVar(value='1')
        self.order = tk.StringVar()
        self.reverse = tk.BooleanVar()
        self.destination = tk.StringVar()
        for row, label, variable in [(0, 'Blockgröße (Bytes):', self.block),
                                     (1, 'Reihenfolge (optional):', self.order),
                                     (3, 'Ausgabedatei:', self.destination)]:
            ttk.Label(options, text=label).grid(row=row, column=0, sticky='w', pady=4)
            entry = ttk.Entry(options, textvariable=variable)
            entry.grid(row=row, column=1, sticky='ew', padx=8)
            self.controls.append(entry)
        check = ttk.Checkbutton(options, text='Bytes innerhalb jedes Blocks umkehren',
                                variable=self.reverse)
        check.grid(row=2, column=1, sticky='w', padx=8, pady=6)
        self.controls.append(check)
        browse = ttk.Button(options, text='Wählen …', command=self.choose_target)
        browse.grid(row=3, column=2)
        self.controls.append(browse)
        ttk.Label(options, text='Leer = Listenreihenfolge. Beispiel: 3,3,1,2,2',
                  foreground='#555555').grid(row=4, column=1, sticky='w', padx=8)
        ttk.Label(self, text='Es werden nur vollständige Blöcke bis zum Ende der kürzesten Datei verarbeitet.',
                  wraplength=720).grid(row=4, sticky='w')
        self.bar = ttk.Progressbar(self, maximum=100)
        self.bar.grid(row=6, sticky='ew', pady=(14, 6))
        self.status = tk.StringVar(value='Bereit.')
        ttk.Label(self, textvariable=self.status).grid(row=7, sticky='w')
        actions = ttk.Frame(self)
        actions.grid(row=8, sticky='e', pady=(12, 0))
        self.start_button = ttk.Button(actions, text='Verarbeiten', command=self.start)
        self.start_button.pack(side='left', padx=8)
        self.stop_button = ttk.Button(actions, text='Abbrechen', command=self.cancel.set, state='disabled')
        self.stop_button.pack(side='left')
        self.build_preview()
        root.protocol('WM_DELETE_WINDOW', self.close)
        self.after(100, self.poll)

    def refresh(self):
        self.listbox.delete(0, 'end')
        for i, path in enumerate(self.files, 1):
            self.listbox.insert('end', f'{i}.  {path}')
        choices = [f'{i}. {Path(p).name}' for i, p in enumerate(self.files, 1)]
        for i, selector in enumerate(self.preview_selectors):
            selector['values'] = choices
            if choices:
                selector.current(min(i, len(choices) - 1))
            else:
                selector.set('')
        self.update_preview()

    def build_preview(self):
        panel = ttk.LabelFrame(self, text='Synchronisierte Hex-/ASCII-Vorschau (maximal zwei Dateien)', padding=8)
        panel.grid(row=5, sticky='nsew', pady=(10, 0))
        panel.columnconfigure((0, 1), weight=1)
        panel.rowconfigure(2, weight=1)
        nav = ttk.Frame(panel)
        nav.grid(row=0, column=0, columnspan=2, sticky='ew')
        self.preview_offset = tk.StringVar(value='0')
        self.preview_swap = tk.BooleanVar()
        ttk.Label(nav, text='Byte-Offset (dezimal oder 0x…):').pack(side='left')
        offset = ttk.Entry(nav, textvariable=self.preview_offset, width=14)
        offset.pack(side='left', padx=6)
        offset.bind('<Return>', lambda e: self.update_preview())
        ttk.Button(nav, text='Anzeigen', command=self.update_preview).pack(side='left')
        ttk.Button(nav, text='◀ 256', command=lambda: self.step_preview(-256)).pack(side='left', padx=4)
        ttk.Button(nav, text='256 ▶', command=lambda: self.step_preview(256)).pack(side='left')
        ttk.Checkbutton(nav, text='Nach Block-Swap', variable=self.preview_swap,
                        command=self.update_preview).pack(side='left', padx=10)
        self.preview_selectors = []
        self.preview_texts = []
        for col in range(2):
            selector = ttk.Combobox(panel, state='readonly')
            selector.grid(row=1, column=col, sticky='ew', padx=4, pady=6)
            selector.bind('<<ComboboxSelected>>', lambda e: self.update_preview())
            self.preview_selectors.append(selector)
            view = tk.Text(panel, height=16, width=68, font=('Consolas', 9), wrap='none', state='disabled')
            view.grid(row=2, column=col, sticky='nsew', padx=4)
            view.bind('<MouseWheel>', self.preview_wheel)
            view.bind('<Key>', lambda e: 'break')
            self.preview_texts.append(view)
        self.preview_note = tk.StringVar(value='Dateien hinzufügen. Mausrad bewegt beide Ansichten gemeinsam.')
        ttk.Label(panel, textvariable=self.preview_note).grid(row=3, column=0, columnspan=2, sticky='w')
        self.block.trace_add('write', lambda *args: self.update_preview())
        self.reverse.trace_add('write', lambda *args: self.update_preview())

    def get_preview_offset(self):
        value = self.preview_offset.get().strip()
        offset = int(value, 16 if value.lower().startswith('0x') else 10)
        if offset < 0:
            raise ValueError('Offset muss mindestens 0 sein.')
        return offset

    def step_preview(self, amount):
        try:
            self.preview_offset.set(str(max(0, self.get_preview_offset() + amount)))
            self.update_preview()
        except ValueError as exc:
            self.preview_note.set(str(exc))

    def preview_wheel(self, event):
        self.step_preview(-16 if event.delta > 0 else 16)
        return 'break'

    def update_preview(self):
        try:
            offset = self.get_preview_offset()
            block = int(self.block.get())
            if block < 1:
                raise ValueError('Blockgröße muss mindestens 1 sein.')
            for selector, view in zip(self.preview_selectors, self.preview_texts):
                index = selector.current()
                content = 'Keine Datei ausgewählt.'
                if 0 <= index < len(self.files):
                    path = Path(self.files[index])
                    with path.open('rb') as source:
                        length = os.fstat(source.fileno()).st_size
                        if self.preview_swap.get() and self.reverse.get():
                            # Map each visible output byte to its input position; bounded memory
                            # even when the selected block size is very large.
                            end = min(offset + 256, length // block * block)
                            data = bytearray()
                            for pos in range(offset, end):
                                start = pos // block * block
                                source.seek(start + block - 1 - pos % block)
                                data.extend(source.read(1))
                        else:
                            source.seek(offset)
                            data = source.read(256)
                            if self.preview_swap.get():
                                data = data[:max(0, length // block * block - offset)]
                    lines = []
                    for start in range(0, len(data), 16):
                        row = data[start:start + 16]
                        hexes = ' '.join(f'{byte:02X}' for byte in row)
                        ascii_text = ''.join(chr(byte) if 32 <= byte < 127 else '.' for byte in row)
                        lines.append(f'{offset + start:08X}  {hexes:<47}  {ascii_text}')
                    content = '\n'.join(lines) if lines else 'Ende der Datei / kein vollständiger Block.'
                view.configure(state='normal')
                view.delete('1.0', 'end')
                view.insert('1.0', content)
                view.configure(state='disabled')
            self.preview_note.set('Gleicher Byte-Offset in beiden Dateien. Mausrad: 16 Bytes. '
                                  '„Nach Block-Swap“ zeigt vollständige Blöcke mit aktueller Swap-Einstellung; keine Merge-Ausgabe.')
        except (ValueError, OSError) as exc:
            self.preview_note.set(f'Vorschau: {exc}')

    def add(self):
        self.files.extend(filedialog.askopenfilenames(title='Eingabedateien auswählen'))
        self.refresh()

    def remove(self):
        for i in reversed(self.listbox.curselection()):
            del self.files[i]
        self.refresh()

    def move(self, delta):
        selected = self.listbox.curselection()
        if len(selected) != 1:
            return
        i = selected[0]
        j = i + delta
        if 0 <= j < len(self.files):
            self.files[i], self.files[j] = self.files[j], self.files[i]
            self.refresh()
            self.listbox.selection_set(j)

    def choose_target(self):
        path = filedialog.asksaveasfilename(title='Ausgabedatei speichern', initialfile='merged.bin')
        if path:
            self.destination.set(path)

    def start(self):
        try:
            size = int(self.block.get())
            if size < 1:
                raise ValueError('Die Blockgröße muss größer als 0 sein.')
            if not self.files:
                raise ValueError('Bitte mindestens eine Datei hinzufügen.')
            parse_order(self.order.get(), len(self.files))
            if not self.destination.get().strip():
                raise ValueError('Bitte eine Ausgabedatei auswählen.')
        except ValueError as exc:
            messagebox.showerror('Eingabe prüfen', str(exc))
            return
        target = Path(self.destination.get())
        if target.exists() and not messagebox.askyesno('Datei ersetzen', f'{target}\n\nErsetzen?'):
            return
        args = (list(self.files), str(target), size, self.order.get(), self.reverse.get())
        self.cancel.clear()
        self.running = True
        self.bar['value'] = 0
        self.status.set('Verarbeitung läuft …')
        for control in self.controls + [self.start_button, self.listbox]:
            control.configure(state='disabled')
        self.stop_button.configure(state='normal')
        threading.Thread(target=self.worker, args=(args,), daemon=True).start()

    def worker(self, args):
        try:
            result = merge_files(*args, progress=lambda done, total:
                                 self.events.put(('progress', (done, total))), cancel=self.cancel)
            self.events.put(('done', f'Fertig. {result:,} Bytes geschrieben.'))
        except Cancelled:
            self.events.put(('done', 'Abgebrochen. Die Ausgabedatei wurde nicht verändert.'))
        except Exception as exc:
            self.events.put(('error', str(exc)))

    def poll(self):
        try:
            while True:
                kind, data = self.events.get_nowait()
                if kind == 'progress':
                    done, total = data
                    self.bar['value'] = 100 * done / total if total else 100
                    self.status.set(f'{done:,} / {total:,} Bytes')
                else:
                    self.running = False
                    for control in self.controls + [self.start_button, self.listbox]:
                        control.configure(state='normal')
                    self.stop_button.configure(state='disabled')
                    self.status.set(data)
                    if kind == 'error':
                        messagebox.showerror('Verarbeitung fehlgeschlagen', data)
        except queue.Empty:
            pass
        self.after(100, self.poll)

    def close(self):
        if self.running:
            self.cancel.set()
            self.status.set('Abbruch läuft … Fenster anschließend erneut schließen.')
        else:
            self.root.destroy()


def main():
    if len(sys.argv) == 1:
        root = tk.Tk()
        App(root)
        root.mainloop()
        return
    parser = argparse.ArgumentParser(description='Dateien blockweise verschachteln (mergeFiles Python).')
    parser.add_argument('files', nargs='+')
    parser.add_argument('-b', type=int, default=1, help='Blockgröße in Bytes')
    parser.add_argument('-r', action='store_true', help='Bytes je Block umkehren')
    parser.add_argument('-o', default='', help='Reihenfolge, z. B. 2,1')
    parser.add_argument('-p', action='store_true', help='Fortschritt anzeigen')
    parser.add_argument('--output', required=True, help='Ausgabedatei')
    parser.add_argument('-v', '--version', action='version', version='mergeFiles Python 1.0 (C 0.3.3)')
    args = parser.parse_args()
    try:
        merge_files(args.files, args.output, args.b, args.o, args.r,
                    progress=(lambda done, total: print(f'\r{done}/{total} Bytes', end='', file=sys.stderr))
                    if args.p else None)
        if args.p:
            print(file=sys.stderr)
    except (ValueError, OSError) as exc:
        parser.exit(1, f'Fehler: {exc}\n')


if __name__ == '__main__':
    main()
