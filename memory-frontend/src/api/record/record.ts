import request from "@/api/request";

export async function selectAll(data: object) {
    return request<API.BaseResponse>('/record/selectAll', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      data: data,
    });
  }

// 批量删除操作记录
export async function deleteRecords(data: number[]) {
  return request<API.BaseResponse>('/record/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
  });
}

// 清空全部操作记录
export async function clearAllRecords() {
  return request<API.BaseResponse>('/record/clearAll', {
    method: 'POST',
  });
}