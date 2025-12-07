# Отчёт о проделанной работе
**Период:** 12 неделя проекта  
**Дата составления:** 28.11.2025  

## Описание проделанной работы

За двенадцатую неделю разработки были успешно выполнены следующие шаги:
- Был изучен материал по сокетам
- Имплементирован ZMQ message broker
- Поднята база данных на postgres
- Разработан python server для работы с Postgres и ZMQ android app

## Поэтапное описание

### Имплементирован ZMQ
В ходе работы создал класс с ZMQ broker'ом, обновил методы в student repository и остальных фрагментах под broker:

```kotlin
class ZmqSockets(private val serverAddress: String) {

    suspend fun sendData(data: String): String = withContext(Dispatchers.IO) {
        var context: ZContext? = null
        var socket: ZMQ.Socket? = null

        try {
            context = ZContext()
            socket = context.createSocket(ZMQ.REQ)
            socket.setReceiveTimeOut(10000)
            socket.connect(serverAddress)

            val sendResult = socket.send(data.toByteArray(ZMQ.CHARSET), 0)
            if (!sendResult) {
                return@withContext "{\"status\":\"error\",\"message\":\"Send failed\"}"
            }

            val reply = socket.recv(0)
            if (reply == null) {
                return@withContext "{\"status\":\"error\",\"message\":\"No response\"}"
            }

            String(reply, ZMQ.CHARSET)

        } catch (e: Exception) {
            "{\"status\":\"error\",\"message\":\"${e.message}\"}"
        } finally {
            socket?.close()
            context?.close()
        }
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        var context: ZContext? = null
        var socket: ZMQ.Socket? = null

        try {
            context = ZContext()
            socket = context.createSocket(ZMQ.REQ)
            socket.setReceiveTimeOut(5000)

            Log.d("ZMQ_TEST", "Connecting to: $serverAddress")
            socket.connect(serverAddress)
            val testData = "{\"operation\":\"test\"}"
            val sendResult = socket.send(testData.toByteArray(ZMQ.CHARSET), 0)
            if (!sendResult) {
                return@withContext "Send failed"
            }

            val reply = socket.recv(0)
            if (reply == null) {
                return@withContext "No response"
            }

            val response = String(reply, ZMQ.CHARSET)
            "Connected: $response"

        } catch (e: Exception) {
            "Connection error: ${e.message}"
        } finally {
            socket?.close()
            context?.close()
        }
    }

}
```

### Поднята postgresql DB и python server
Для работы с DB был развёрнут python server с поддержкой добавления, обновления , удаления юзера согласно zmq messages из android app. Основной код:

```python
def start_server(self):
        try:
            self.socket.bind(f"tcp://*:{self.zmq_port}")
            logger.info(f"Server started on port {self.zmq_port}")
            
            while True:
                message=self.socket.recv_string()
                logger.info(f"Received message length: {len(message)}")
                
                try:
                    result=self.process_student_message(message)
                    response={"status": "success", "message": result}
                    logger.info(f"Processing result: {result}")
                except Exception as e:
                    logger.error(f"Error: {e}")
                    response={"status": "error", "message": str(e)}
                
                self.socket.send_string(json.dumps(response))
                
        except Exception as e:
            logger.error(f"Server error: {e}")
        finally:
            self.socket.close()
            self.context.term()

def process_single_student(self, data):
        if data.get('operation') == 'test':
            return "Test connection successful"
        
        connection=psycopg2.connect(**self.db_config)
        try:
            cursor=connection.cursor()
            operation=data.get('operation')
            student_data=data.get('data', {})
            
            android_id=student_data.get('id')
            name=student_data.get('studentName')
            group=student_data.get('studentGroup')
            nfc_id=student_data.get('studentNFC')
            attendance=student_data.get('attendance', False)
            
            logger.info(f"Processing single: {name} - {group}")
            if operation == "delete":
                if operation == "delete":
                    if nfc_id and nfc_id != '':
                        cursor.execute("DELETE FROM students WHERE student_nfc=%s", (nfc_id,))
                        deleted_count=cursor.rowcount
                        result=f"Deleted by NFC: {name}"
                    else:
                        cursor.execute("DELETE FROM students WHERE student_name=%s AND student_group=%s", (name, group))
                        deleted_count=cursor.rowcount
                        result=f"Deleted by name/group: {name}"
            else:
                cursor.execute("""
                INSERT INTO students 
                (android_id, student_name, student_group, student_nfc, attendance, operation_type)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (student_nfc) 
                DO UPDATE SET
                    student_name=EXCLUDED.student_name,
                    student_group=EXCLUDED.student_group,
                    attendance=EXCLUDED.attendance,
                    operation_type=EXCLUDED.operation_type, 
                    synced_at=CURRENT_TIMESTAMP
                """, (android_id, name, group, nfc_id, attendance, operation))
                result=f"Processed {name}"
            connection.commit()
            return result
            
        except Exception as e:
            connection.rollback()
            raise Exception(f"Database error: {e}")
        finally:
            connection.close()
```

### Результаты

<img width="1172" height="708" alt="Снимок экрана 2025-12-01 011656" src="https://github.com/user-attachments/assets/ed12e6ad-94ef-4eb2-8a7b-4be2a519adf3" />

## TODO
- Сделать синхронизацию с сервером (при запуске)
- Провести рефакторинг кода
