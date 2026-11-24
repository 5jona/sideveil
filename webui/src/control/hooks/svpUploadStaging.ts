import { type ExecResult, shellQuote } from '@gojona/bridge';
import { SIDEVEIL_METHODS } from '../../shared/types.ts';

/** Error shown when a Ctl-only plugin admin RPC is attempted from the embedded host WebView. */
export const HOST_ADMIN_BLOCKED_MESSAGE = '插件管理操作仅支持在 KernelSU 控制中心执行';

/**
 * Maximum base64 characters per `ksu.exec` shell invocation.
 * Linux `MAX_ARG_STRLEN` is 128 KiB per `argv` string; 40,000 ASCII bytes leaves ample room
 * for command boilerplate and shell quoting overhead.
 */
export const SVP_UPLOAD_CHUNK_CHARS = 40_000;

export const CTL_ONLY_ADMIN_METHODS: ReadonlySet<string> = new Set([
  SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE,
  SIDEVEIL_METHODS.PLUGINS_INSTALL,
  SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES,
  SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS,
  SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW,
  SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED,
  SIDEVEIL_METHODS.PLUGINS_SET_EMBED_CONFIG,
  SIDEVEIL_METHODS.PLUGINS_UNINSTALL,
]);

export type ShellExecFn = (cmd: string) => Promise<ExecResult>;

export interface StagedSvpArchive {
  b64Path: string;
  svpPath: string;
  cleanup: () => Promise<void>;
}

export interface StageSvpArchiveOptions {
  chunkSize?: number;
  nonce?: string;
}

/** Rejects Ctl-only plugin administration RPCs when invoked from the embedded sidebar host env. */
export function assertAdminRpcAllowed(env: string, method: string): void {
  if (env === 'host' && CTL_ONLY_ADMIN_METHODS.has(method)) {
    throw new Error(HOST_ADMIN_BLOCKED_MESSAGE);
  }
}

/** Strips whitespace and splits a base64 string into bounded shell-safe chunks. */
export function splitBase64IntoChunks(
  base64: string,
  chunkSize = SVP_UPLOAD_CHUNK_CHARS,
): string[] {
  const compact = base64.replace(/\s+/g, '');
  if (!compact) {
    throw new Error('插件归档数据为空');
  }
  const boundedSize = Math.max(1, Math.min(Math.trunc(chunkSize), 40_960));
  const chunks: string[] = [];
  for (let offset = 0; offset < compact.length; offset += boundedSize) {
    chunks.push(compact.slice(offset, offset + boundedSize));
  }
  return chunks;
}

function createUploadNonce(): string {
  const timePart = Date.now().toString(36);
  const randPart = Math.random().toString(36).slice(2, 10);
  return `${timePart}_${randPart}`;
}

function formatExecFailure(res: ExecResult, fallback: string): string {
  return res.stderr.trim() || res.stdout.trim() || `${fallback} (errno ${res.errno})`;
}

/**
 * Stages a base64-encoded `.svp` archive onto `/data/local/tmp/` in `<= 40 KiB` shell chunks
 * and decodes it via `base64 -d`, avoiding KernelSU `argv` `E2BIG` failures on large packages.
 * Callers must invoke `cleanup()` in a `finally` block (or use `withStagedSvpArchiveForKsu`).
 */
export async function stageSvpArchiveForKsu(
  exec: ShellExecFn,
  base64: string,
  options?: StageSvpArchiveOptions,
): Promise<StagedSvpArchive> {
  const chunks = splitBase64IntoChunks(base64, options?.chunkSize);
  const rawNonce = (options?.nonce ?? createUploadNonce()).replace(/[^a-zA-Z0-9_-]/g, '');
  const nonce = rawNonce || createUploadNonce();
  const b64Path = `/data/local/tmp/sideveil_upload_${nonce}.b64`;
  const svpPath = `/data/local/tmp/sideveil_upload_${nonce}.svp`;
  const cleanup = async () => {
    await exec(`rm -f ${shellQuote(b64Path)} ${shellQuote(svpPath)}`).catch(() => {});
  };

  try {
    const initRes = await exec(`umask 077 && : > ${shellQuote(b64Path)}`);
    if (initRes.errno !== 0) {
      throw new Error(formatExecFailure(initRes, '初始化临时上传文件失败'));
    }

    for (const chunk of chunks) {
      const writeRes = await exec(`printf '%s' ${shellQuote(chunk)} >> ${shellQuote(b64Path)}`);
      if (writeRes.errno !== 0) {
        throw new Error(formatExecFailure(writeRes, '写入插件分片数据失败'));
      }
    }

    const decodeRes = await exec(
      `umask 077 && base64 -d ${shellQuote(b64Path)} > ${shellQuote(svpPath)} && rm -f ${shellQuote(b64Path)}`,
    );
    if (decodeRes.errno !== 0) {
      throw new Error(formatExecFailure(decodeRes, '解码插件归档失败'));
    }

    return { b64Path, svpPath, cleanup };
  } catch (err) {
    await cleanup();
    throw err;
  }
}

/** Stages a base64 `.svp` upload for KernelSU, runs `fn(svpPath)`, and always removes temp files. */
export async function withStagedSvpArchiveForKsu<T>(
  exec: ShellExecFn,
  base64: string,
  fn: (svpPath: string) => Promise<T>,
  options?: StageSvpArchiveOptions,
): Promise<T> {
  const staged = await stageSvpArchiveForKsu(exec, base64, options);
  try {
    return await fn(staged.svpPath);
  } finally {
    await staged.cleanup();
  }
}
