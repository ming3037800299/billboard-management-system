/**
 * 展示格式化工具与租赁状态元信息。
 */

/** 报价格式化：空值显示"面议"，数字加千分位。 */
export function fmtPrice(price) {
  if (price === null || price === undefined || price === '') return '面议'
  const n = Number(price)
  if (Number.isNaN(n)) return '面议'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

/** 时间格式化：ISO 字符串 → yyyy-MM-dd HH:mm。 */
export function fmtDateTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (x) => String(x).padStart(2, '0')
  return (
    d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
  )
}

/** 租赁状态元信息（标签 / 主题色 / 徽章样式类）。 */
const STATUS = {
  AVAILABLE: { label: '可租赁', color: '#16a34a', cls: 'st-available' },
  RESERVED: { label: '已预定', color: '#a9660f', cls: 'st-reserved' },
  RENTED: { label: '已租赁', color: '#e11d48', cls: 'st-rented' },
  PAUSED: { label: '暂停出租', color: '#d97706', cls: 'st-paused' },
}

export function statusInfo(status) {
  return STATUS[status] || { label: status || '未知', color: '#64748b', cls: '' }
}

/** HTML 转义（地图信息窗等需要手工拼 HTML 的场景使用；模板中无需，Vue 默认转义）。 */
export function escapeHtml(value) {
  if (value === null || value === undefined) return ''
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}
