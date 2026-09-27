import { useRef, useState } from 'react';

interface ApkDropzoneProps {
  accept: string;
  extensions: string[];
  maxBytes: number;
  disabled?: boolean;
  busy?: boolean;
  progress?: number | null;
  fileName?: string | null;
  title: string;
  hint: string;
  busyLabel?: string;
  onFile: (file: File) => void;
  onError: (message: string) => void;
}

const formatSize = (bytes: number) => bytes >= 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(bytes >= 10 * 1024 * 1024 ? 0 : 1)} MiB`
  : `${Math.max(1, Math.round(bytes / 1024))} KiB`;

export function ApkDropzone({
  accept, extensions, maxBytes, disabled = false, busy = false, progress = null,
  fileName, title, hint, busyLabel = 'Uploading and verifying…', onFile, onError,
}: ApkDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const dragDepth = useRef(0);
  const [over, setOver] = useState(false);

  const choose = (file?: File) => {
    if (!file || disabled || busy) return;
    const lower = file.name.toLowerCase();
    if (!extensions.some((ext) => lower.endsWith(ext))) {
      onError(`Choose a ${extensions.join(', ')} file.`);
      return;
    }
    if (file.size > maxBytes) {
      onError(`${file.name} is ${formatSize(file.size)}. Maximum size is ${formatSize(maxBytes)}.`);
      return;
    }
    onFile(file);
  };

  const openPicker = () => {
    if (!disabled && !busy) inputRef.current?.click();
  };

  return <div
    className={`dropzone ${over ? 'over' : ''} ${busy ? 'busy' : ''} ${disabled ? 'disabled' : ''}`}
    role="button"
    tabIndex={disabled || busy ? -1 : 0}
    aria-disabled={disabled || busy}
    onClick={openPicker}
    onKeyDown={(event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault(); openPicker();
      }
    }}
    onDragEnter={(event) => {
      event.preventDefault();
      dragDepth.current += 1;
      if (!disabled && !busy) setOver(true);
    }}
    onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = disabled || busy ? 'none' : 'copy'; }}
    onDragLeave={(event) => {
      event.preventDefault();
      dragDepth.current = Math.max(0, dragDepth.current - 1);
      if (dragDepth.current === 0) setOver(false);
    }}
    onDrop={(event) => {
      event.preventDefault(); dragDepth.current = 0; setOver(false);
      if (event.dataTransfer.files.length > 1) {
        onError('Drop one file at a time.'); return;
      }
      choose(event.dataTransfer.files[0]);
    }}
  >
    <input ref={inputRef} type="file" accept={accept} hidden disabled={disabled || busy}
      onChange={(event) => { choose(event.target.files?.[0]); event.target.value = ''; }} />
    <span className="dz-icon" aria-hidden="true">{busy ? <span className="spin" /> : fileName ? '✓' : '↑'}</span>
    <span className="dz-main">{busy ? `${busyLabel}${fileName ? ` ${fileName}` : ''}` : fileName ? fileName : over ? 'Release file to upload' : title}</span>
    <span className="dz-sub">{busy && progress != null ? `${progress}% uploaded` : fileName && !busy ? 'Drop another file to replace it' : hint}</span>
    {busy && progress != null && <span className="dz-progress" role="progressbar" aria-label="Upload progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}><span style={{ width: `${progress}%` }} /></span>}
  </div>;
}
