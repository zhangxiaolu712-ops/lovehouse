// ===== 恋爱小屋 · 配置文件 =====
const LOVEHOUSE_CONFIG = {
  supabase: {
    url: 'https://cvyguanuaxcypsvoozeo.supabase.co',
    key: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImN2eWd1YW51YXhjeXBzdm9vemVvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQzMDYzMTMsImV4cCI6MjA5OTg4MjMxM30.bj-NKf82ahA-zvUtW-pMmfY56Q-Ansh8ezh9K-T65SI'
  },
  owner: '小婷',
  title: '我们的恋爱小屋'
};

// API请求头
const HEADERS = {
  'apikey': LOVEHOUSE_CONFIG.supabase.key,
  'Authorization': `Bearer ${LOVEHOUSE_CONFIG.supabase.key}`,
  'Content-Type': 'application/json'
};

// 数据库操作
const DB = {
  async get(table, params = '') {
    const res = await fetch(`${LOVEHOUSE_CONFIG.supabase.url}/rest/v1/${table}?${params}`, { headers: HEADERS });
    if (!res.ok) throw new Error('获取数据失败');
    return res.json();
  },

  async post(table, body) {
    const res = await fetch(`${LOVEHOUSE_CONFIG.supabase.url}/rest/v1/${table}`, {
      method: 'POST',
      headers: { ...HEADERS, 'Prefer': 'return=representation' },
      body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error('保存失败');
    return res.json();
  },

  async delete(table, id) {
    const res = await fetch(`${LOVEHOUSE_CONFIG.supabase.url}/rest/v1/${table}?id=eq.${id}`, {
      method: 'DELETE',
      headers: HEADERS
    });
    return res.ok;
  }
};

// 格式化日期
function formatDate(str) {
  const d = new Date(str);
  return `${d.getFullYear()}.${String(d.getMonth()+1).padStart(2,'0')}.${String(d.getDate()).padStart(2,'0')}`;
}

function formatDateTime(str) {
  const d = new Date(str);
  return `${formatDate(str)} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
}
