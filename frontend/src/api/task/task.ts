import request from '@/api/request'

export async function listAsyncTasks(
  params: { current?: number; size?: number; status?: API.AsyncTaskDetail['status'] } = {},
  signal?: AbortSignal,
) {
  return request<API.BaseResponse>('/asyncTask/list', {
    method: 'GET',
    params,
    signal,
  })
}

export async function getAsyncTask(taskId: number, signal?: AbortSignal) {
  return request<API.BaseResponse>(`/asyncTask/${taskId}`, {
    method: 'GET',
    signal,
  })
}

export async function retryAsyncTask(taskId: number) {
  return request<API.BaseResponse>(`/asyncTask/${taskId}/retry`, {
    method: 'POST',
  })
}
