import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import type { Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHistory } from 'vue-router'

import { LOCALE_STORAGE_KEY, i18n } from '@/i18n'

/**
 * T102: every chrome-bearing component renders Indonesian when the locale is `id`.
 *
 * The rest of the suite is pinned to English, so without this file a component whose copy was
 * never moved into the catalog (or whose key is misspelled in `id.json` only) would pass every
 * spec. One row per component; each asserts a string that exists ONLY in the Indonesian catalog.
 * Every fixture is invented.
 */

// chart.js needs a canvas context and a ResizeObserver, neither of which jsdom has. The chart
// itself is not under test here — the card's copy is.
vi.mock('chart.js', () => {
  class Chart {
    static register() {}
    data: unknown
    options: Record<string, unknown>
    constructor(_canvas: unknown, config: { data: unknown; options?: Record<string, unknown> }) {
      this.data = config.data
      this.options = config.options ?? {}
    }
    update() {}
    destroy() {}
  }
  const noop = {}
  return {
    Chart,
    BarController: noop,
    BarElement: noop,
    CategoryScale: noop,
    Legend: noop,
    LinearScale: noop,
    LineController: noop,
    LineElement: noop,
    PointElement: noop,
    Tooltip: noop,
  }
})

vi.mock('@/apis/metrics', async () => {
  const actual = await vi.importActual<typeof import('@/apis/metrics')>('@/apis/metrics')
  return {
    ...actual,
    fetchKpi: vi.fn<typeof actual.fetchKpi>(),
    fetchTrend: vi.fn<typeof actual.fetchTrend>(),
    fetchSlaBuckets: vi.fn<typeof actual.fetchSlaBuckets>(),
    fetchMatrix: vi.fn<typeof actual.fetchMatrix>(),
    fetchTopCategories: vi.fn<typeof actual.fetchTopCategories>(),
    fetchFreshness: vi.fn<typeof actual.fetchFreshness>(),
    fetchScopeCounts: vi.fn<typeof actual.fetchScopeCounts>(),
    fetchWallboard: vi.fn<typeof actual.fetchWallboard>(),
  }
})
vi.mock('@/apis/incidents', async () => {
  const actual = await vi.importActual<typeof import('@/apis/incidents')>('@/apis/incidents')
  return { ...actual, fetchIncidents: vi.fn<typeof actual.fetchIncidents>() }
})
vi.mock('@/apis/admin', async () => {
  const actual = await vi.importActual<typeof import('@/apis/admin')>('@/apis/admin')
  return {
    ...actual,
    fetchUsers: vi.fn<typeof actual.fetchUsers>(),
    fetchRoles: vi.fn<typeof actual.fetchRoles>(),
    fetchUser: vi.fn<typeof actual.fetchUser>(),
  }
})

import { fetchUsers, fetchRoles } from '@/apis/admin'
import { fetchIncidents } from '@/apis/incidents'
import {
  fetchFreshness,
  fetchKpi,
  fetchMatrix,
  fetchScopeCounts,
  fetchSlaBuckets,
  fetchTopCategories,
  fetchTrend,
  fetchWallboard,
  type KpiResponse,
} from '@/apis/metrics'
import CreatedClosedTrend from '@/components/charts/CreatedClosedTrend.vue'
import ImpactUrgencyHeatmap from '@/components/charts/ImpactUrgencyHeatmap.vue'
import MttrComparison from '@/components/charts/MttrComparison.vue'
import SlaBucketBars from '@/components/charts/SlaBucketBars.vue'
import TopCategories from '@/components/charts/TopCategories.vue'
import WorkaroundDebtCard from '@/components/charts/WorkaroundDebtCard.vue'
import FreshnessIndicator from '@/components/common/FreshnessIndicator.vue'
import InfoHint from '@/components/common/InfoHint.vue'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import IncidentFilterBar from '@/components/filters/IncidentFilterBar.vue'
import KpiCard from '@/components/kpi/KpiCard.vue'
import KpiRow from '@/components/kpi/KpiRow.vue'
import ScopeCounts from '@/components/kpi/ScopeCounts.vue'
import IncidentTable from '@/components/table/IncidentTable.vue'
import AppLayout from '@/layout/AppLayout.vue'
import AppSidebar from '@/layout/AppSidebar.vue'
import WallboardLayout from '@/layout/WallboardLayout.vue'
import { useAuthStore } from '@/stores/auth'
import UserFormView from '@/views/admin/UserFormView.vue'
import UserTableView from '@/views/admin/UserTableView.vue'
import ChangePasswordView from '@/views/ChangePasswordView.vue'
import ExecView from '@/views/ExecView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import OpsView from '@/views/OpsView.vue'
import WallboardView from '@/views/WallboardView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
})

const KPI: KpiResponse = {
  windowFrom: null,
  windowTo: null,
  windowDays: null,
  backlog: 0,
  open: 4,
  closed: 9,
  breached: 1,
  atRisk: 2,
  mttrDays: 2.5,
  mttrWorkaroundDays: 1.5,
  mttrPermanentDays: 3.25,
  mttrGapDays: 1.75,
  workaroundDebt: 3,
  previousPeriod: null,
}

let wrapper: VueWrapper | null = null

beforeEach(async () => {
  setActivePinia(createPinia())
  useAuthStore().menus = [
    { menuId: 'incident.ops', label: 'Operations', route: '/ops', privileges: ['view', 'export'] },
    { menuId: 'incident.problem', label: 'Problem', route: '/problem', privileges: ['view'] },
    { menuId: 'incident.sr', label: 'SR', route: '/service-requests', privileges: ['view'] },
  ]
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
  vi.mocked(fetchKpi).mockResolvedValue(KPI)
  vi.mocked(fetchTrend).mockResolvedValue([])
  vi.mocked(fetchSlaBuckets).mockResolvedValue([])
  vi.mocked(fetchMatrix).mockResolvedValue([])
  vi.mocked(fetchScopeCounts).mockResolvedValue({ scopes: [] })
  vi.mocked(fetchWallboard).mockRejectedValue(new Error('not under test'))
  vi.mocked(fetchTopCategories).mockResolvedValue([])
  vi.mocked(fetchFreshness).mockResolvedValue({
    lastRunAt: '2026-08-17T03:00:00Z',
    lastSourceTs: '2026-08-17T02:58:00Z',
    ageSeconds: 120,
    stale: false,
    staleReason: null,
  })
  vi.mocked(fetchIncidents).mockResolvedValue({
    content: [],
    page: 0,
    size: 50,
    totalElements: 0,
    totalPages: 0,
  })
  vi.mocked(fetchUsers).mockResolvedValue({
    content: [],
    page: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
  })
  vi.mocked(fetchRoles).mockResolvedValue([])
  await router.push('/ops')
  await router.isReady()
  // Through storage as well as the instance: a layout with the language switch creates the locale
  // store, which (correctly) re-applies the stored preference on creation.
  localStorage.setItem(LOCALE_STORAGE_KEY, 'id')
  i18n.global.locale.value = 'id'
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

interface Row {
  name: string
  component: Component
  props?: Record<string, unknown>
  /** A string only the Indonesian catalog produces. Checked against the rendered HTML, so attributes count. */
  indonesian: string
}

const noop = () => {}

const ROWS: Row[] = [
  {
    name: 'KpiCard',
    component: KpiCard,
    props: { label: 'x', value: null, error: true },
    indonesian: 'Angka ini gagal dimuat',
  },
  { name: 'KpiRow', component: KpiRow, indonesian: 'Indikator utama' },
  { name: 'CreatedClosedTrend', component: CreatedClosedTrend, indonesian: 'Dibuat / Ditutup' },
  { name: 'SlaBucketBars', component: SlaBucketBars, indonesian: 'Kelompok SLA' },
  {
    name: 'ImpactUrgencyHeatmap',
    component: ImpactUrgencyHeatmap,
    props: { cells: [] },
    indonesian: 'Dampak × Urgensi',
  },
  {
    name: 'MttrComparison',
    component: MttrComparison,
    props: { kpi: KPI },
    indonesian: 'Waktu ke Solusi Permanen',
  },
  {
    name: 'TopCategories',
    component: TopCategories,
    props: { categories: [] },
    indonesian: 'Kategori Teratas',
  },
  {
    name: 'WorkaroundDebtCard',
    component: WorkaroundDebtCard,
    props: { kpi: KPI },
    indonesian: 'Utang workaround',
  },
  { name: 'FreshnessIndicator', component: FreshnessIndicator, indonesian: 'terakhir diperbarui' },
  {
    name: 'InfoHint',
    component: InfoHint,
    props: { term: 'closed' },
    indonesian: 'Tentang Ditutup',
  },
  { name: 'LanguageSwitch', component: LanguageSwitch, indonesian: 'Bahasa' },
  { name: 'IncidentFilterBar', component: IncidentFilterBar, indonesian: 'Filter lainnya' },
  {
    name: 'IncidentTable',
    component: IncidentTable,
    props: { rows: [], page: 0, size: 25, totalElements: 0 },
    indonesian: 'Tidak ada insiden yang cocok dengan filter ini',
  },
  { name: 'AppLayout', component: AppLayout, indonesian: 'Langsung ke konten utama' },
  {
    name: 'AppSidebar',
    component: AppSidebar,
    props: { hidden: false, scrim: false, closeSidebar: noop, logout: noop },
    // The three ticket-group menus, in the UI language rather than the server's English label.
    indonesian: 'Permintaan Layanan',
  },
  { name: 'ScopeCounts', component: ScopeCounts, indonesian: 'Kelompok tiket' },
  { name: 'WallboardLayout', component: WallboardLayout, indonesian: 'Kembali ke dasbor' },
  { name: 'LoginView', component: LoginView, indonesian: 'Selamat datang kembali' },
  {
    name: 'ChangePasswordView',
    component: ChangePasswordView,
    indonesian: 'Konfirmasi kata sandi baru',
  },
  { name: 'NotFoundView', component: NotFoundView, indonesian: 'Halaman tidak ditemukan' },
  { name: 'ExecView', component: ExecView, indonesian: 'Bulan ini' },
  { name: 'OpsView', component: OpsView, indonesian: '0 insiden cocok dengan filter saat ini' },
  { name: 'WallboardView', component: WallboardView, indonesian: 'Wallboard insiden' },
  { name: 'WallboardView rotation', component: WallboardView, indonesian: 'Layar 1 dari 2' },
  { name: 'UserTableView', component: UserTableView, indonesian: 'Buat Pengguna' },
  {
    name: 'UserFormView',
    component: UserFormView,
    props: { mode: 'create' },
    indonesian: 'Masukkan nama pengguna',
  },
]

describe('Indonesian rendering', () => {
  it.each(ROWS)(
    '$name renders its chrome in Indonesian',
    async ({ component, props, indonesian }) => {
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      wrapper = mount(component, {
        props,
        attachTo: document.body,
        global: { plugins: [router, PrimeVue, ToastService, [VueQueryPlugin, { queryClient }]] },
      })
      await flushPromises()

      expect(wrapper.html()).toContain(indonesian)
    },
  )

  it('keeps data values untranslated next to translated chrome', async () => {
    wrapper = mount(ImpactUrgencyHeatmap, {
      props: {
        cells: [
          { impact: 'Unclassified', urgency: 'Unknown (77)', count: 1234 },
          { impact: 'Tinggi', urgency: 'Unknown (77)', count: 2 },
        ],
      },
    })

    const text = wrapper.text()
    expect(text).toContain('Unclassified')
    expect(text).toContain('Unknown (77)')
    // Intl, not toFixed: an Indonesian reader sees a dot as the thousands separator.
    expect(text).toContain('Total: 1.236')
  })
})
