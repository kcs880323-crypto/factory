// Claude 아티팩트 화면에서 링크 보관함·설정을 그 사람만 보는 저장소(db 기능의 data/users/<id>)에 둔다.
// 브라우저 저장소만 쓰면 다른 기기에서 안 보이고 지워질 수도 있어서다. 아티팩트가 아니면 아무것도 하지 않는다.
type Snap = { exists: boolean; data(): Record<string, unknown> | undefined };
type DocRef = { get(): Promise<Snap>; set(d: Record<string, unknown>): Promise<void>; delete(): Promise<void> };
type Coll = { doc(id: string): DocRef };
type DbNs = { collection(path: string): Coll };
type UserNs = { id(): Promise<string | null> };
type ClaudeGlobal = { use(name: string): Promise<unknown> };

/** 서버에 함께 두는 키 (호출 기록·캐시는 브라우저에만) */
export const SYNCED_KEYS = ['cp.settings', 'cp.links', 'cp.checklist'];

let collection: Promise<Coll | null> | null = null;

function claude(): ClaudeGlobal | null {
  const c = (globalThis as { claude?: ClaudeGlobal }).claude;
  return c && typeof c.use === 'function' ? c : null;
}

function withTimeout<T>(p: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(fallback), ms);
    p.then(
      (v) => {
        clearTimeout(t);
        resolve(v);
      },
      () => {
        clearTimeout(t);
        resolve(fallback);
      },
    );
  });
}

export function personalCollection(): Promise<Coll | null> {
  if (!collection) {
    collection = (async () => {
      const c = claude();
      if (!c) return null;
      const [db, user] = (await Promise.all([c.use('db'), c.use('user')])) as [DbNs | null, UserNs | null];
      if (!db || !user) return null;
      const id = await user.id();
      return id ? db.collection(`data/users/${id}`) : null;
    })().catch(() => null);
  }
  return collection;
}

/** 서버 값: 있으면 값, 없거나 못 읽으면 undefined (그때는 브라우저 저장소를 쓴다) */
export async function remoteGet(key: string): Promise<unknown> {
  const coll = await withTimeout(personalCollection(), 12000, null);
  if (!coll) return undefined;
  const snap = await withTimeout(coll.doc(key).get(), 8000, null);
  return snap?.exists ? snap.data()?.v : undefined;
}

// 같은 문서에는 한 번에 하나씩만 쓴다.
const queues = new Map<string, Promise<void>>();

export function remoteSet(key: string, value: unknown): Promise<void> {
  const prev = queues.get(key) ?? Promise.resolve();
  const next = prev
    .catch(() => undefined)
    .then(async () => {
      const coll = await personalCollection();
      if (coll) await coll.doc(key).set({ v: value as Record<string, unknown> });
    });
  queues.set(key, next);
  return next;
}

export async function remoteDelete(key: string): Promise<void> {
  const coll = await personalCollection();
  if (coll) await coll.doc(key).delete();
}
